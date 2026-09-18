package com.admin.equipment.attachment;

import com.admin.equipment.model.AppUser;
import com.admin.equipment.model.attachment.AttachmentUpload;
import com.admin.equipment.model.attachment.EvidenceAttachment;
import com.admin.equipment.model.inspection.InspectionAbnormality;
import com.admin.equipment.repo.AppUserRepository;
import com.admin.equipment.repo.attachment.AttachmentUploadRepository;
import com.admin.equipment.repo.attachment.EvidenceAttachmentRepository;
import com.admin.equipment.repo.inspection.InspectionAbnormalityRepository;
import com.admin.equipment.service.attachment.AttachmentApiException;
import com.admin.equipment.service.attachment.AttachmentProperties;
import com.admin.equipment.service.attachment.AttachmentService;
import com.admin.equipment.service.attachment.AttachmentStorage;
import com.admin.equipment.service.attachment.dto.AttachmentView;
import com.admin.equipment.service.attachment.dto.ChunkReceiptResult;
import com.admin.equipment.service.attachment.dto.UploadInitiateResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 受控附件服务测试：断点续传、重复完成、并发绑定、完整性降级、
 * 校验和/大小/类型门控、引用保护与清理。
 */
@SpringBootTest
class AttachmentServiceTest {

    @Autowired private AttachmentService service;
    @Autowired private AttachmentStorage storage;
    @Autowired private AttachmentProperties props;
    @Autowired private InspectionAbnormalityRepository abnormalityRepo;
    @Autowired private EvidenceAttachmentRepository attachmentRepo;
    @Autowired private AttachmentUploadRepository uploadRepo;
    @Autowired private AppUserRepository userRepo;

    private AppUser uploader;

    @BeforeEach
    void cleanStorage() throws IOException {
        Files.walkFileTree(storage.getBaseDir(), new java.nio.file.SimpleFileVisitor<>() {
            @Override
            public java.nio.file.FileVisitResult visitFile(Path f, java.nio.file.attribute.BasicFileAttributes a) throws IOException {
                Files.deleteIfExists(f);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
            @Override
            public java.nio.file.FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException {
                // 保留根目录结构
                if (!d.equals(storage.getBaseDir())) Files.deleteIfExists(d);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
        storage.init();
        uploader = userRepo.findByUsername("admin").orElseThrow();
    }

    private InspectionAbnormality newAbnormality() {
        InspectionAbnormality ab = new InspectionAbnormality();
        ab.setTaskId(9001L);
        ab.setTaskPointId(8001L);
        ab.setRecordId(7001L);
        ab.setEquipmentId(6001L);
        ab.setTitle("电机异响");
        ab.setDescription("声音较大");
        ab.setSeverity("high");
        ab.setStatus("reported");
        return abnormalityRepo.save(ab);
    }

    private byte[] payload(int size) {
        byte[] b = new byte[size];
        for (int i = 0; i < size; i++) b[i] = (byte) (i % 251);
        return b;
    }

    private String sha256(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    private List<byte[]> split(byte[] data, int chunkSize) {
        int n = (data.length + chunkSize - 1) / chunkSize;
        var list = new java.util.ArrayList<byte[]>();
        for (int i = 0; i < n; i++) {
            int from = i * chunkSize;
            int to = Math.min(data.length, from + chunkSize);
            byte[] part = new byte[to - from];
            System.arraycopy(data, from, part, 0, part.length);
            list.add(part);
        }
        return list;
    }

    private UploadInitiateResult startSession(InspectionAbnormality ab, byte[] data,
                                              String hash, int chunkSize, String mediaType, String filename) {
        return service.initiate(ab.getId(), filename, mediaType, data.length,
                (long) chunkSize, hash, uploader);
    }

    // ----------------------------------------------------------------
    // 1. 断点续传：分片缺失时完成失败，查询缺失序号，补齐后成功绑定
    // ----------------------------------------------------------------

    @Test
    void resumableUpload_outOfOrderAndGapThenComplete() throws Exception {
        byte[] data = payload(2500);
        InspectionAbnormality ab = newAbnormality();
        int chunkSize = 1000;
        UploadInitiateResult init = startSession(ab, data, sha256(data), chunkSize,
                "audio/mpeg", "电机异响录音.mp3");
        assertEquals(3, init.totalChunks());

        List<byte[]> chunks = split(data, chunkSize);

        // 乱序先传 2、0，模拟中断后续传
        ChunkReceiptResult r2 = service.uploadChunk(init.uploadKey(), 2, new ByteArrayInputStream(chunks.get(2)));
        assertTrue(r2.receivedChunks().contains(2));
        ChunkReceiptResult r0 = service.uploadChunk(init.uploadKey(), 0, new ByteArrayInputStream(chunks.get(0)));
        assertEquals(List.of(0, 2), r0.receivedChunks());
        assertEquals(List.of(1), r0.missingChunks());
        assertFalse(r0.completed());

        // 缺分片时完成必须失败且不得绑定
        AttachmentApiException ex = assertThrows(AttachmentApiException.class,
                () -> service.complete(init.uploadKey()));
        assertTrue(ex.getMessage().contains("分片不完整"));
        assertEquals(0, attachmentRepo.countByAbnormalityId(ab.getId()));

        // 会话状态仍可查询，支持客户端断点续传
        UploadInitiateResult status = service.getUploadStatus(init.uploadKey());
        assertEquals(List.of(0, 2), status.receivedChunks());

        // 同一分片幂等重传不报错
        ChunkReceiptResult retry0 = service.uploadChunk(init.uploadKey(), 0, new ByteArrayInputStream(chunks.get(0)));
        assertEquals(List.of(0, 2), retry0.receivedChunks());

        // 补齐最后一个分片
        ChunkReceiptResult r1 = service.uploadChunk(init.uploadKey(), 1, new ByteArrayInputStream(chunks.get(1)));
        assertTrue(r1.completed());

        AttachmentView view = service.complete(init.uploadKey());
        assertEquals("ok", view.integrityStatus());
        assertTrue(view.contentAvailable());
        assertEquals(ab.getId(), view.abnormalityId());
        assertEquals(9001L, view.taskId());
        assertEquals("audio", view.category());
        assertEquals(uploader.getId(), view.uploadedById());
        assertEquals(data.length, view.fileSize());
        assertEquals(sha256(data), view.checksumSha256());
    }

    // ----------------------------------------------------------------
    // 2. 重复完成：串行重复调用只产生一份证据
    // ----------------------------------------------------------------

    @Test
    void duplicateComplete_isIdempotent_singleAttachment() throws Exception {
        byte[] data = payload(1800);
        InspectionAbnormality ab = newAbnormality();
        UploadInitiateResult init = startSession(ab, data, sha256(data), 1000,
                "image/png", "铭牌.png");
        List<byte[]> chunks = split(data, 1000);
        for (int i = 0; i < chunks.size(); i++) {
            service.uploadChunk(init.uploadKey(), i, new ByteArrayInputStream(chunks.get(i)));
        }

        AttachmentView first = service.complete(init.uploadKey());
        AttachmentView second = service.complete(init.uploadKey());
        AttachmentView third = service.complete(init.uploadKey());

        assertEquals(first.id(), second.id());
        assertEquals(second.id(), third.id());
        assertEquals(1, attachmentRepo.countByAbnormalityId(ab.getId()));
        assertEquals(1, storage.listEvidenceFiles().size());
        // 完成后临时分片被清理
        assertEquals(0, storage.listSessionDirs().size());
    }

    // ----------------------------------------------------------------
    // 3. 并发绑定：多线程同时完成同一会话，只有一个成功者落库
    // ----------------------------------------------------------------

    @Test
    void concurrentComplete_bindsExactlyOnce() throws Exception {
        byte[] data = payload(4200);
        InspectionAbnormality ab = newAbnormality();
        UploadInitiateResult init = startSession(ab, data, sha256(data), 1000,
                "application/pdf", "检测报告.pdf");
        List<byte[]> chunks = split(data, 1000);
        for (int i = 0; i < chunks.size(); i++) {
            service.uploadChunk(init.uploadKey(), i, new ByteArrayInputStream(chunks.get(i)));
        }

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<AttachmentView>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return service.complete(init.uploadKey());
            }));
        }
        start.countDown();
        Long boundId = null;
        for (Future<AttachmentView> f : futures) {
            AttachmentView v = f.get(30, TimeUnit.SECONDS);
            if (boundId == null) boundId = v.id();
            else assertEquals(boundId, v.id());
        }
        pool.shutdown();

        assertEquals(1, attachmentRepo.countByAbnormalityId(ab.getId()));
        assertEquals(1, storage.listEvidenceFiles().size());
    }

    // ----------------------------------------------------------------
    // 4. 校验和/声明大小/允许类型门控
    // ----------------------------------------------------------------

    @Test
    void wrongChecksum_isRejectedAndNotBound() throws Exception {
        byte[] data = payload(1200);
        InspectionAbnormality ab = newAbnormality();
        // 另造一份不同内容的摘要作为“客户端声明值”
        byte[] other = payload(1200);
        other[0] ^= 0xFF;
        String otherHash = sha256(other);

        UploadInitiateResult init = startSession(ab, data, otherHash, 1000,
                "audio/wav", "noise.wav");
        List<byte[]> chunks = split(data, 1000);
        for (int i = 0; i < chunks.size(); i++) {
            service.uploadChunk(init.uploadKey(), i, new ByteArrayInputStream(chunks.get(i)));
        }
        AttachmentApiException ex = assertThrows(AttachmentApiException.class,
                () -> service.complete(init.uploadKey()));
        assertTrue(ex.getMessage().contains("校验和不匹配"), ex.getMessage());
        assertEquals(0, attachmentRepo.countByAbnormalityId(ab.getId()));
        assertEquals(0, storage.listEvidenceFiles().size());
        // 会话保留，客户端可重传问题分片后重试，而非整个流程作废
        assertEquals(AttachmentUpload.STATUS_UPLOADING,
                uploadRepo.findByUploadKey(init.uploadKey()).orElseThrow().getStatus());
    }

    @Test
    void wrongChunkSize_isRejected() throws Exception {
        byte[] data = payload(1500);
        InspectionAbnormality ab = newAbnormality();
        UploadInitiateResult init = startSession(ab, data, sha256(data), 1000,
                "image/jpeg", "motor.jpg");
        // 实际发 999 字节，声明期望 1000
        AttachmentApiException ex = assertThrows(AttachmentApiException.class,
                () -> service.uploadChunk(init.uploadKey(), 0, new ByteArrayInputStream(new byte[999])));
        assertTrue(ex.getMessage().contains("大小不合法"));
    }

    @Test
    void disallowedMediaType_isRejectedAtInitiate() {
        InspectionAbnormality ab = newAbnormality();
        // 可执行类型不在白名单
        assertThrows(AttachmentApiException.class,
                () -> service.initiate(ab.getId(), "x.exe", "application/x-msdownload",
                        100, 100L, "0".repeat(64), uploader));
        // 摘要格式非法
        assertThrows(AttachmentApiException.class,
                () -> service.initiate(ab.getId(), "x", "audio/mpeg",
                        100, 100L, "abc", uploader));
        // 文件名为 .. 等非法值
        assertThrows(AttachmentApiException.class,
                () -> service.initiate(ab.getId(), "..", "image/png",
                        100, 100L, "0".repeat(64), uploader));
    }

    @Test
    void traversalFilename_isSanitized() throws Exception {
        byte[] data = payload(120);
        InspectionAbnormality ab = newAbnormality();
        // 文件名带路径穿越片段，存储名只使用服务端 key，原始名仅收敛为 basename
        UploadInitiateResult init = service.initiate(ab.getId(), "a/b/../../铭牌.png",
                "image/png", data.length, 1000L, sha256(data), uploader);
        assertEquals("铭牌.png", init.originalFilename());
        service.uploadChunk(init.uploadKey(), 0, new ByteArrayInputStream(data));
        AttachmentView v = service.complete(init.uploadKey());
        EvidenceAttachment row = attachmentRepo.findById(v.id()).orElseThrow();
        // 元数据路径不含任何穿越片段，物理文件被约束在证据根目录内
        assertFalse(row.getStoragePath().contains(".."));
        Path physical = storage.resolveSafely(row.getStoragePath());
        assertTrue(physical.startsWith(storage.getEvidenceRoot()));
    }

    // ----------------------------------------------------------------
    // 5. 数据库记录存在但物理文件丢失：降级视图 + 410 下载语义
    // ----------------------------------------------------------------

    @Test
    void missingPhysicalFile_degradesGracefully() throws Exception {
        AttachmentView bound = fullUpload(newAbnormality(), payload(700), "audio/ogg", "a.ogg");
        EvidenceAttachment att = attachmentRepo.findById(bound.id()).orElseThrow();
        Path physical = storage.resolveSafely(att.getStoragePath());

        // 管理员误删物理文件
        Files.deleteIfExists(physical);

        AttachmentView degraded = service.statusOf(bound.id());
        assertFalse(degraded.contentAvailable());
        assertEquals("missing", degraded.integrityStatus());
        // 来源信息仍可追溯
        assertEquals(9001L, degraded.taskId());
        assertEquals(uploader.getId(), degraded.uploadedById());

        // 强制复核同样返回降级状态而不是抛异常
        AttachmentView verified = service.verify(bound.id());
        assertEquals("missing", verified.integrityStatus());

        // 下载前解析明确给出 contentAvailable=false
        assertFalse(service.openDownload(bound.id()).contentAvailable());

        // 闭环记录列表同样可展示降级状态
        List<AttachmentView> list = service.listByAbnormality(att.getAbnormalityId(), false);
        assertEquals("missing", list.get(0).integrityStatus());
    }

    @Test
    void tamperedFile_isDetectedByHashVerification() throws Exception {
        AttachmentView bound = fullUpload(newAbnormality(), payload(700), "image/png", "a.png");
        EvidenceAttachment att = attachmentRepo.findById(bound.id()).orElseThrow();
        Path physical = storage.resolveSafely(att.getStoragePath());
        // 等大小篡改：存在性/大小探测发现不了，强制哈希复核必须发现
        byte[] tampered = Files.readAllBytes(physical);
        tampered[10] ^= 0x01;
        Files.write(physical, tampered);
        AttachmentView v = service.verify(bound.id());
        assertEquals("corrupt", v.integrityStatus());
        assertFalse(v.contentAvailable());
    }

    // ----------------------------------------------------------------
    // 删除边界：已引用阻止删除；未引用允许删除并清理物理文件
    // ----------------------------------------------------------------

    @Test
    void referencedAttachment_cannotBeDeleted() throws Exception {
        AttachmentView bound = fullUpload(newAbnormality(), payload(300), "text/plain", "note.txt");
        AttachmentApiException ex = assertThrows(AttachmentApiException.class,
                () -> service.delete(bound.id()));
        assertTrue(ex.getMessage().contains("禁止删除"));
        assertTrue(attachmentRepo.findById(bound.id()).isPresent());
    }

    @Test
    void unreferencedAttachment_isDeletedWithPhysicalFile() throws Exception {
        AttachmentView bound = fullUpload(newAbnormality(), payload(300), "text/plain", "note.txt");
        EvidenceAttachment att = attachmentRepo.findById(bound.id()).orElseThrow();
        Path physical = storage.resolveSafely(att.getStoragePath());
        assertTrue(Files.exists(physical));

        // 解除业务引用（模拟异常记录本身被合规删除后的孤立元数据）
        att.setAbnormalityId(null);
        att.setWorkOrderId(null);
        attachmentRepo.save(att);

        service.delete(bound.id());
        assertTrue(attachmentRepo.findById(bound.id()).isEmpty());
        assertFalse(Files.exists(physical));
    }

    // ----------------------------------------------------------------
    // 异常转工单：保留引用而不是复制文件
    // ----------------------------------------------------------------

    @Test
    void linkToWorkOrder_keepsSinglePhysicalFile() throws Exception {
        InspectionAbnormality ab = newAbnormality();
        AttachmentView a1 = fullUpload(ab, payload(200), "image/png", "p1.png");
        AttachmentView a2 = fullUpload(ab, payload(240), "audio/mpeg", "a1.mp3");

        int linked = service.linkToWorkOrder(ab.getId(), 555L);
        assertEquals(2, linked);
        assertEquals(2, attachmentRepo.findByWorkOrderIdOrderByCreatedAtDesc(555L).size());
        // 只有两份物理文件，没有复制产生第三/四份
        assertEquals(2, storage.listEvidenceFiles().size());
        assertNotEquals(a1.id(), a2.id());
    }

    // ----------------------------------------------------------------
    // 过期会话清理
    // ----------------------------------------------------------------

    @Test
    void expiredSession_isMarkedAndTempCleaned() throws Exception {
        byte[] data = payload(500);
        InspectionAbnormality ab = newAbnormality();
        UploadInitiateResult init = startSession(ab, data, sha256(data), 1000,
                "image/png", "x.png");
        service.uploadChunk(init.uploadKey(), 0, new ByteArrayInputStream(data));
        assertTrue(storage.resolveSessionTempDir(init.uploadKey()).toFile().exists());

        AttachmentUpload row = uploadRepo.findByUploadKey(init.uploadKey()).orElseThrow();
        row.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        uploadRepo.save(row);

        // 过期会话拒绝继续上传
        assertThrows(AttachmentApiException.class,
                () -> service.uploadChunk(init.uploadKey(), 0, new ByteArrayInputStream(data)));

        var result = service.cleanupExpiredSessions();
        assertTrue(result.expiredSessions() >= 1);
        assertFalse(storage.resolveSessionTempDir(init.uploadKey()).toFile().exists());
        assertEquals(AttachmentUpload.STATUS_EXPIRED,
                uploadRepo.findByUploadKey(init.uploadKey()).orElseThrow().getStatus());
    }

    @Test
    void garbageCollect_removesOrphanPhysicalFiles() throws Exception {
        AttachmentView bound = fullUpload(newAbnormality(), payload(120), "text/plain", "keep.txt");
        EvidenceAttachment att = attachmentRepo.findById(bound.id()).orElseThrow();
        Path physical = storage.resolveSafely(att.getStoragePath());

        // 元数据被直接删除（绕过服务），物理文件成为孤儿
        attachmentRepo.deleteById(att.getId());
        attachmentRepo.flush();
        assertTrue(Files.exists(physical));

        var r = service.garbageCollect();
        assertTrue(r.orphanEvidenceFiles() >= 1);
        assertFalse(Files.exists(physical));
    }

    private AttachmentView fullUpload(InspectionAbnormality ab, byte[] data, String mediaType, String filename)
            throws Exception {
        UploadInitiateResult init = startSession(ab, data, sha256(data), 1000, mediaType, filename);
        List<byte[]> chunks = split(data, 1000);
        for (int i = 0; i < chunks.size(); i++) {
            service.uploadChunk(init.uploadKey(), i, new ByteArrayInputStream(chunks.get(i)));
        }
        return service.complete(init.uploadKey());
    }
}
