package com.admin.equipment.attachment;

import com.admin.equipment.model.attachment.Attachment;
import com.admin.equipment.model.attachment.AttachmentLink;
import com.admin.equipment.model.inspection.InspectionAbnormality;
import com.admin.equipment.model.inspection.InspectionTask;
import com.admin.equipment.repo.EquipmentRepository;
import com.admin.equipment.repo.WorkOrderRepository;
import com.admin.equipment.repo.attachment.AttachmentLinkRepository;
import com.admin.equipment.repo.attachment.AttachmentRepository;
import com.admin.equipment.repo.inspection.InspectionAbnormalityRepository;
import com.admin.equipment.repo.inspection.InspectionTaskRepository;
import com.admin.equipment.service.attachment.AttachmentProperties;
import com.admin.equipment.service.attachment.AttachmentService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 受控附件链路集成测试（H2 内存库 + 临时附件目录 + 真实 JWT 过滤器）：
 * 断点续传、重复完成、并发绑定、非法路径、文件丢失降级、
 * 校验失败清理、绑定核验、异常转工单引用保留与删除保护。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:attachment_test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "app.attachment.chunk-size-bytes=16",
        "app.attachment.max-size-bytes=1024",
        "app.attachment.session-expire-hours=1",
        "app.attachment.allowed-media-types=image/png,image/jpeg,audio/wav,audio/mpeg,application/pdf,text/plain"
})
@AutoConfigureMockMvc
class AttachmentEvidenceFlowTest {

    /** 附件根目录：静态初始化，保证在 Spring 上下文装配前就绪 */
    private static final Path ATTACH_DIR = createTempDir();

    @DynamicPropertySource
    static void attachmentDir(DynamicPropertyRegistry registry) {
        registry.add("app.attachment.dir", () -> ATTACH_DIR.toString());
    }

    private static Path createTempDir() {
        try {
            return Files.createTempDirectory("attachment-test");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired AttachmentService attachmentService;
    @Autowired AttachmentProperties props;
    @Autowired AttachmentRepository attachmentRepo;
    @Autowired AttachmentLinkRepository linkRepo;
    @Autowired InspectionAbnormalityRepository abnormalityRepo;
    @Autowired InspectionTaskRepository taskRepo;
    @Autowired WorkOrderRepository workOrderRepo;
    @Autowired EquipmentRepository equipmentRepo;

    private String token;

    @BeforeEach
    void setUp() throws Exception {
        linkRepo.deleteAll();
        attachmentRepo.deleteAll();
        abnormalityRepo.deleteAll();
        workOrderRepo.deleteAll();
        taskRepo.deleteAll();
        wipeStorageDir();
        token = login("admin", "admin123");
    }

    // ------------------------------------------------------------------
    // 分段上传：断点续传 / 重复完成 / 范围下载 / 鉴权
    // ------------------------------------------------------------------

    @Test
    void chunkedUploadResumeDuplicateCompleteAndRangeDownload() throws Exception {
        byte[] content = new byte[40];
        for (int i = 0; i < content.length; i++) content[i] = (byte) (i * 7 + 3);

        long uploadId = initUpload("motor-noise.wav", content.length, sha256Hex(content), "audio/wav");

        // 乱序上传：0、2 先到，模拟中断后查询续传状态
        putChunk(uploadId, 0, Arrays.copyOfRange(content, 0, 16));
        putChunk(uploadId, 2, Arrays.copyOfRange(content, 32, 40));

        MvcResult session = mvc.perform(get("/api/attachments/uploads/{id}", uploadId)
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uploadedChunks[0]").value(0))
                .andExpect(jsonPath("$.uploadedChunks[1]").value(2))
                .andReturn();
        assertEquals(2, objectMapper.readTree(session.getResponse().getContentAsString())
                .get("uploadedChunks").size());

        // 断点续传：补上缺失的分片 1；分片 0 重复上传（幂等覆盖）
        putChunk(uploadId, 1, Arrays.copyOfRange(content, 16, 32));
        putChunk(uploadId, 0, Arrays.copyOfRange(content, 0, 16));

        // 完成确认
        mvc.perform(post("/api/attachments/uploads/{id}/complete", uploadId)
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("completed"))
                .andExpect(jsonPath("$.sha256").value(sha256Hex(content)));

        // 重复完成：幂等返回同一条记录
        mvc.perform(post("/api/attachments/uploads/{id}/complete", uploadId)
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(uploadId))
                .andExpect(jsonPath("$.status").value("completed"));
        assertEquals(1, attachmentRepo.count());

        // 完整下载
        MvcResult full = mvc.perform(get("/api/attachments/{id}/download", uploadId)
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(header().string("Accept-Ranges", "bytes"))
                .andExpect(header().string("Content-Type", "audio/wav"))
                .andReturn();
        assertArrayEquals(content, full.getResponse().getContentAsByteArray());

        // 范围读取
        MvcResult range = mvc.perform(get("/api/attachments/{id}/download", uploadId)
                        .header("Authorization", bearer())
                        .header("Range", "bytes=5-9"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string("Content-Range", "bytes 5-9/40"))
                .andReturn();
        assertArrayEquals(Arrays.copyOfRange(content, 5, 10), range.getResponse().getContentAsByteArray());

        // 开放式范围 bytes=32- → 末尾 8 字节
        MvcResult tail = mvc.perform(get("/api/attachments/{id}/download", uploadId)
                        .header("Authorization", bearer())
                        .header("Range", "bytes=32-"))
                .andExpect(status().isPartialContent())
                .andReturn();
        assertArrayEquals(Arrays.copyOfRange(content, 32, 40), tail.getResponse().getContentAsByteArray());

        // 越界范围 → 416
        mvc.perform(get("/api/attachments/{id}/download", uploadId)
                        .header("Authorization", bearer())
                        .header("Range", "bytes=100-200"))
                .andExpect(status().isRequestedRangeNotSatisfiable())
                .andExpect(header().string("Content-Range", "bytes */40"));

        // 未携带 JWT → 401
        mvc.perform(get("/api/attachments/{id}/download", uploadId))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------
    // 校验失败与过期：临时分片可清理
    // ------------------------------------------------------------------

    @Test
    void checksumMismatchFailsAndCleanupRemovesTempChunks() throws Exception {
        byte[] content = "motor noise".getBytes(StandardCharsets.UTF_8);
        String wrongSha = "0".repeat(64);
        long uploadId = initUpload("noise.wav", content.length, wrongSha, "audio/wav");
        putChunk(uploadId, 0, content);

        mvc.perform(post("/api/attachments/uploads/{id}/complete", uploadId)
                        .header("Authorization", bearer()))
                .andExpect(status().isUnprocessableEntity());
        Attachment failed = attachmentRepo.findById(uploadId).orElseThrow();
        assertEquals(Attachment.STATUS_FAILED, failed.getStatus());
        assertTrue(failed.getFailReason().contains("SHA-256"));

        // 失败会话的临时分片仍在磁盘上，等待清理
        Path tmpDir = ATTACH_DIR.resolve("tmp").resolve(String.valueOf(uploadId));
        assertTrue(Files.exists(tmpDir.resolve("0.part")));

        mvc.perform(post("/api/attachments/uploads/cleanup").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.removedTempDirs").value(1));
        assertFalse(Files.exists(tmpDir));
    }

    @Test
    void expiredSessionRejectsChunksAndIsCleanedUp() throws Exception {
        byte[] content = new byte[40];
        long uploadId = initUpload("a.wav", content.length, sha256Hex(content), "audio/wav");
        putChunk(uploadId, 0, Arrays.copyOfRange(content, 0, 16));

        // 强制会话过期
        Attachment a = attachmentRepo.findById(uploadId).orElseThrow();
        a.setExpiresAt(LocalDateTime.now().minusHours(1));
        attachmentRepo.save(a);

        mvc.perform(put("/api/attachments/uploads/{id}/chunks/{index}", uploadId, 1)
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(Arrays.copyOfRange(content, 16, 32)))
                .andExpect(status().isUnprocessableEntity());
        assertEquals(Attachment.STATUS_EXPIRED, attachmentRepo.findById(uploadId).orElseThrow().getStatus());

        mvc.perform(post("/api/attachments/uploads/cleanup").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.removedTempDirs").value(1));
        assertFalse(Files.exists(ATTACH_DIR.resolve("tmp").resolve(String.valueOf(uploadId))));
    }

    @Test
    void initUploadValidatesTypeSizeAndChecksum() throws Exception {
        byte[] content = "x".getBytes(StandardCharsets.UTF_8);
        String sha = sha256Hex(content);

        // 不允许的媒体类型
        mvc.perform(post("/api/attachments/uploads")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(uploadJson("evil.exe", 1L, sha, "application/x-msdownload")))
                .andExpect(status().isUnprocessableEntity());
        // 声明大小非法
        mvc.perform(post("/api/attachments/uploads")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(uploadJson("a.wav", 0L, sha, "audio/wav")))
                .andExpect(status().isUnprocessableEntity());
        // 超出大小上限（测试配置 1024 字节）
        mvc.perform(post("/api/attachments/uploads")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(uploadJson("a.wav", 1025L, sha, "audio/wav")))
                .andExpect(status().isUnprocessableEntity());
        // SHA-256 格式非法
        mvc.perform(post("/api/attachments/uploads")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(uploadJson("a.wav", 1L, "not-a-sha", "audio/wav")))
                .andExpect(status().isUnprocessableEntity());
        assertEquals(0, attachmentRepo.count());
    }

    // ------------------------------------------------------------------
    // 绑定：三重核验 + 幂等 + 并发
    // ------------------------------------------------------------------

    @Test
    void bindRequiresVerifiedAttachmentAndIsIdempotent() throws Exception {
        InspectionAbnormality ab = newAbnormality("电机异响");
        byte[] content = "nameplate photo bytes".getBytes(StandardCharsets.UTF_8);
        long attId = completedAttachment("nameplate.png", content, "image/png");

        // 正常绑定
        mvc.perform(post("/api/inspection/abnormalities/{id}/attachments", ab.getId())
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attachmentId\":" + attId + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.bizType").value("inspection_abnormality"));
        // 重复绑定：幂等，不产生第二条引用
        mvc.perform(post("/api/inspection/abnormalities/{id}/attachments", ab.getId())
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attachmentId\":" + attId + "}"))
                .andExpect(status().isCreated());
        assertEquals(1, linkRepo.findByAttachmentId(attId).size());

        // 未完成上传的附件不能绑定
        long uploadingId = initUpload("draft.wav", 10, "a".repeat(64), "audio/wav");
        mvc.perform(post("/api/inspection/abnormalities/{id}/attachments", ab.getId())
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attachmentId\":" + uploadingId + "}"))
                .andExpect(status().isUnprocessableEntity());

        // 文件被篡改（校验和不匹配）后不能绑定
        Attachment att = attachmentRepo.findById(attId).orElseThrow();
        byte[] tampered = content.clone();
        tampered[0] ^= 0x7F;
        Files.write(ATTACH_DIR.resolve("files").resolve(att.getStorageKey()), tampered);
        InspectionAbnormality ab2 = newAbnormality("另一异常");
        mvc.perform(post("/api/inspection/abnormalities/{id}/attachments", ab2.getId())
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attachmentId\":" + attId + "}"))
                .andExpect(status().isUnprocessableEntity());

        // 绑定不存在的异常 → 404
        mvc.perform(post("/api/inspection/abnormalities/{id}/attachments", 999999L)
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attachmentId\":" + uploadingId + "}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void concurrentBindingCreatesExactlyOneLink() throws Exception {
        InspectionAbnormality ab = newAbnormality("并发绑定目标");
        long attId = completedAttachment("noise.wav", "concurrent".getBytes(StandardCharsets.UTF_8), "audio/wav");

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                try {
                    go.await(5, TimeUnit.SECONDS);
                    attachmentService.bindToAbnormality(attId, ab.getId(), "并发测试");
                } catch (Throwable t) {
                    errors.add(t);
                }
            }));
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        go.countDown();
        for (Future<?> f : futures) f.get(15, TimeUnit.SECONDS);
        pool.shutdown();

        assertTrue(errors.isEmpty(), "并发绑定不应报错: " + errors);
        List<AttachmentLink> links = linkRepo.findByAttachmentId(attId);
        assertEquals(1, links.size(), "并发绑定必须只产生一条引用");
        assertEquals(ab.getId(), links.get(0).getBizId());
    }

    // ------------------------------------------------------------------
    // 路径穿越与文件丢失降级
    // ------------------------------------------------------------------

    @Test
    void pathTraversalStorageKeyIsRejected() throws Exception {
        // 在附件根目录之外放置敏感文件，模拟穿越目标
        Path secret = ATTACH_DIR.resolve("..").normalize().resolve("top-secret.txt");
        Files.write(secret, "TOP-SECRET".getBytes(StandardCharsets.UTF_8));
        try {
            // 直接篡改数据库记录（绕过服务端生成存储键的正常路径）
            Attachment evil = new Attachment();
            evil.setStorageKey("../top-secret.txt");
            evil.setOriginalName("evil.png");
            evil.setMediaType("image/png");
            evil.setCategory("image");
            evil.setSizeBytes(10L);
            evil.setSha256("0".repeat(64));
            evil.setStatus(Attachment.STATUS_COMPLETED);
            evil.setExpiresAt(LocalDateTime.now().plusHours(1));
            evil = attachmentRepo.save(evil);

            MvcResult res = mvc.perform(get("/api/attachments/{id}/download", evil.getId())
                            .header("Authorization", bearer()))
                    .andExpect(status().isBadRequest())
                    .andReturn();
            assertFalse(res.getResponse().getContentAsString().contains("TOP-SECRET"),
                    "路径穿越不得泄露目录外文件内容");
        } finally {
            Files.deleteIfExists(secret);
        }
    }

    @Test
    void missingFileProducesDegradedResponses() throws Exception {
        InspectionAbnormality ab = newAbnormality("电机异响");
        byte[] content = "evidence".getBytes(StandardCharsets.UTF_8);
        long attId = completedAttachment("noise.wav", content, "audio/wav");
        mvc.perform(post("/api/inspection/abnormalities/{id}/attachments", ab.getId())
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attachmentId\":" + attId + "}"))
                .andExpect(status().isCreated());

        // 数据库记录仍在，物理文件丢失
        Attachment att = attachmentRepo.findById(attId).orElseThrow();
        Files.delete(ATTACH_DIR.resolve("files").resolve(att.getStorageKey()));

        // 下载 → 410 降级响应，不暴露内部路径
        mvc.perform(get("/api/attachments/{id}/download", attId)
                        .header("Authorization", bearer()))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("丢失")));

        // 元数据接口 → 完整性状态 missing
        mvc.perform(get("/api/attachments/{id}", attId)
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.integrity").value("missing"));

        // 异常闭环记录的证据列表 → 该证据完整性为 missing
        mvc.perform(get("/api/inspection/abnormalities/{id}/attachments", ab.getId())
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.evidences[0].integrity").value("missing"));

        // 文件丢失的附件不允许再绑定到其他异常
        InspectionAbnormality ab2 = newAbnormality("另一异常");
        mvc.perform(post("/api/inspection/abnormalities/{id}/attachments", ab2.getId())
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attachmentId\":" + attId + "}"))
                .andExpect(status().isUnprocessableEntity());
    }

    // ------------------------------------------------------------------
    // 异常转工单：引用保留与删除保护
    // ------------------------------------------------------------------

    @Test
    void workOrderConversionKeepsReferencesAndDeleteIsGuarded() throws Exception {
        Long equipmentId = equipmentRepo.findAll().get(0).getId();
        InspectionTask task = newTask();
        long audioId = completedAttachment("motor-noise.wav",
                "noise recording".getBytes(StandardCharsets.UTF_8), "audio/wav");
        long photoId = completedAttachment("nameplate.png",
                "nameplate photo".getBytes(StandardCharsets.UTF_8), "image/png");

        // 巡检员上报异常并随单绑定证据 → 自动转工单
        MvcResult report = mvc.perform(post("/api/inspection/tasks/abnormality/report")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"
                                + "\"taskId\":" + task.getId() + ","
                                + "\"taskPointId\":1,"
                                + "\"equipmentId\":" + equipmentId + ","
                                + "\"title\":\"电机异响\","
                                + "\"description\":\"声音较大\","
                                + "\"severity\":\"high\","
                                + "\"workOrderType\":\"repair\","
                                + "\"attachmentIds\":[" + audioId + "," + photoId + "]"
                                + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.workOrderCreated").value(true))
                .andReturn();
        JsonNode abJson = objectMapper.readTree(report.getResponse().getContentAsString());
        long abId = abJson.get("id").asLong();
        long woId = abJson.get("workOrderId").asLong();

        // 引用保留：每份证据同时挂在异常与工单上，文件只有一份
        assertEquals(2, linkRepo.findByAttachmentId(audioId).size());
        assertEquals(2, linkRepo.findByAttachmentId(photoId).size());
        assertEquals(2, Files.list(ATTACH_DIR.resolve("files")).count());

        // 质量工程师视角：异常闭环记录的证据带完整性状态与来源
        mvc.perform(get("/api/inspection/abnormalities/{id}/attachments", abId)
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.evidences.length()").value(2))
                .andExpect(jsonPath("$.evidences[0].integrity").value("ok"))
                .andExpect(jsonPath("$.evidences[0].attachment.uploadedByName").value("平台管理员"))
                .andExpect(jsonPath("$.evidences[0].references.length()").value(2));

        // 工单视角：可引用同样的证据
        mvc.perform(get("/api/work-orders/{id}/attachments", woId)
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.evidences.length()").value(2));

        // 已引用证据禁止删除
        mvc.perform(delete("/api/attachments/{id}", audioId)
                        .header("Authorization", bearer()))
                .andExpect(status().isConflict());
        assertTrue(attachmentRepo.findById(audioId).isPresent());

        // 解除异常引用后仍被工单引用 → 依然禁止删除
        mvc.perform(delete("/api/inspection/abnormalities/{id}/attachments/{attId}", abId, audioId)
                        .header("Authorization", bearer()))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/api/attachments/{id}", audioId)
                        .header("Authorization", bearer()))
                .andExpect(status().isConflict());

        // 未引用附件可删除：元数据与文件一起消失
        long orphanId = completedAttachment("temp.txt", "orphan".getBytes(StandardCharsets.UTF_8), "text/plain");
        String orphanKey = attachmentRepo.findById(orphanId).orElseThrow().getStorageKey();
        mvc.perform(delete("/api/attachments/{id}", orphanId)
                        .header("Authorization", bearer()))
                .andExpect(status().isNoContent());
        assertTrue(attachmentRepo.findById(orphanId).isEmpty());
        assertFalse(Files.exists(ATTACH_DIR.resolve("files").resolve(orphanKey)));
    }

    @Test
    void reportWithUnverifiedAttachmentIsRejected() throws Exception {
        Long equipmentId = equipmentRepo.findAll().get(0).getId();
        InspectionTask task = newTask();
        // 只发起会话不完成 → 不能随异常上报绑定
        long uploadingId = initUpload("draft.wav", 10, "b".repeat(64), "audio/wav");
        mvc.perform(post("/api/inspection/tasks/abnormality/report")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"
                                + "\"taskId\":" + task.getId() + ","
                                + "\"taskPointId\":1,"
                                + "\"equipmentId\":" + equipmentId + ","
                                + "\"title\":\"电机异响\","
                                + "\"attachmentIds\":[" + uploadingId + "]"
                                + "}"))
                .andExpect(status().isUnprocessableEntity());
        assertEquals(0, abnormalityRepo.count(), "绑定失败时整个上报应回滚");
    }

    // ------------------------------------------------------------------
    // 辅助方法
    // ------------------------------------------------------------------

    private String bearer() {
        return "Bearer " + token;
    }

    private String login(String username, String password) throws Exception {
        MvcResult res = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).get("access_token").asText();
    }

    private String uploadJson(String filename, Long size, String sha256, String mediaType) {
        return "{\"filename\":\"" + filename + "\",\"size\":" + size
                + ",\"sha256\":\"" + sha256 + "\",\"mediaType\":\"" + mediaType + "\"}";
    }

    private long initUpload(String filename, long size, String sha256, String mediaType) throws Exception {
        MvcResult res = mvc.perform(post("/api/attachments/uploads")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(uploadJson(filename, size, sha256, mediaType)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).get("uploadId").asLong();
    }

    private void putChunk(long uploadId, int index, byte[] data) throws Exception {
        mvc.perform(put("/api/attachments/uploads/{id}/chunks/{index}", uploadId, index)
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(data))
                .andExpect(status().isOk());
    }

    /** 走完整 API 流程上传并完成一个附件，返回附件 ID。 */
    private long completedAttachment(String filename, byte[] content, String mediaType) throws Exception {
        long uploadId = initUpload(filename, content.length, sha256Hex(content), mediaType);
        int chunk = (int) props.getChunkSizeBytes();
        for (int offset = 0, index = 0; offset < content.length; offset += chunk, index++) {
            putChunk(uploadId, index,
                    Arrays.copyOfRange(content, offset, Math.min(offset + chunk, content.length)));
        }
        mvc.perform(post("/api/attachments/uploads/{id}/complete", uploadId)
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("completed"));
        return uploadId;
    }

    private InspectionAbnormality newAbnormality(String title) {
        InspectionAbnormality ab = new InspectionAbnormality();
        ab.setTaskId(1L);
        ab.setTaskPointId(1L);
        ab.setTitle(title);
        ab.setDescription("现场描述");
        ab.setStatus("reported");
        return abnormalityRepo.save(ab);
    }

    private InspectionTask newTask() {
        InspectionTask task = new InspectionTask();
        task.setPlanId(1L);
        task.setCode("TK-TEST-" + System.nanoTime());
        task.setTemplateId(1L);
        task.setStatus("in_progress");
        return taskRepo.save(task);
    }

    private void wipeStorageDir() throws IOException {
        for (String sub : List.of("files", "tmp")) {
            Path dir = ATTACH_DIR.resolve(sub);
            if (!Files.exists(dir)) continue;
            try (Stream<Path> s = Files.walk(dir)) {
                s.filter(p -> !p.equals(dir))
                        .sorted(Comparator.reverseOrder())
                        .forEach(p -> {
                            try {
                                Files.deleteIfExists(p);
                            } catch (IOException e) {
                                throw new UncheckedIOException(e);
                            }
                        });
            }
        }
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
