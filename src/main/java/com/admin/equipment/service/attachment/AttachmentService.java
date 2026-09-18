package com.admin.equipment.service.attachment;

import com.admin.equipment.model.AppUser;
import com.admin.equipment.model.attachment.AttachmentChunkReceipt;
import com.admin.equipment.model.attachment.AttachmentUpload;
import com.admin.equipment.model.attachment.EvidenceAttachment;
import com.admin.equipment.model.inspection.InspectionAbnormality;
import com.admin.equipment.repo.attachment.AttachmentChunkReceiptRepository;
import com.admin.equipment.repo.attachment.AttachmentUploadRepository;
import com.admin.equipment.repo.attachment.EvidenceAttachmentRepository;
import com.admin.equipment.repo.inspection.InspectionAbnormalityRepository;
import com.admin.equipment.service.attachment.dto.AttachmentView;
import com.admin.equipment.service.attachment.dto.ChunkReceiptResult;
import com.admin.equipment.service.attachment.dto.UploadInitiateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 受控附件服务：
 * 1) 分片上传只写临时区，完成时必须同时满足 SHA-256、声明大小、允许类型三项校验才绑定异常；
 * 2) 完成接口以悲观锁 + 状态机保证重复/并发完成幂等，只产生一份证据；
 * 3) 异常转工单只回填 workOrderId 引用，不复制文件；
 * 4) 删除已引用证据一律拒绝；未引用附件在事务提交后再删物理文件；
 * 5) 过期/失败会话的临时分片与孤儿物理文件可显式或定时清理。
 */
@Service
public class AttachmentService {

    private static final Logger log = LoggerFactory.getLogger(AttachmentService.class);

    private static final int MAX_TOTAL_CHUNKS = 10_000;

    private static final Map<String, String> CATEGORY_BY_PREFIX = Map.of(
            "image/", "image",
            "audio/", "audio"
    );
    private static final Set<String> DOCUMENT_TYPES = Set.of(
            "application/pdf", "text/plain", "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    private static final Map<String, String> EXT_BY_TYPE = Map.ofEntries(
            Map.entry("image/jpeg", ".jpg"),
            Map.entry("image/png", ".png"),
            Map.entry("image/webp", ".webp"),
            Map.entry("audio/mpeg", ".mp3"),
            Map.entry("audio/mp4", ".m4a"),
            Map.entry("audio/aac", ".aac"),
            Map.entry("audio/wav", ".wav"),
            Map.entry("audio/x-wav", ".wav"),
            Map.entry("audio/ogg", ".ogg"),
            Map.entry("audio/webm", ".webm"),
            Map.entry("application/pdf", ".pdf"),
            Map.entry("text/plain", ".txt"),
            Map.entry("application/msword", ".doc"),
            Map.entry("application/vnd.openxmlformats-officedocument.wordprocessingml.document", ".docx")
    );

    private final AttachmentProperties props;
    private final AttachmentStorage storage;
    private final AttachmentUploadRepository uploadRepo;
    private final AttachmentChunkReceiptRepository chunkRepo;
    private final EvidenceAttachmentRepository attachmentRepo;
    private final InspectionAbnormalityRepository abnormalityRepo;
    private final TransactionTemplate txTemplate;
    private final SecureRandom random = new SecureRandom();

    public AttachmentService(AttachmentProperties props,
                             AttachmentStorage storage,
                             AttachmentUploadRepository uploadRepo,
                             AttachmentChunkReceiptRepository chunkRepo,
                             EvidenceAttachmentRepository attachmentRepo,
                             InspectionAbnormalityRepository abnormalityRepo,
                             org.springframework.transaction.PlatformTransactionManager txManager) {
        this.props = props;
        this.storage = storage;
        this.uploadRepo = uploadRepo;
        this.chunkRepo = chunkRepo;
        this.attachmentRepo = attachmentRepo;
        this.abnormalityRepo = abnormalityRepo;
        this.txTemplate = new TransactionTemplate(txManager);
    }

    // ------------------------------------------------------------------
    // 初始化 / 分片 / 完成
    // ------------------------------------------------------------------

    @Transactional
    public UploadInitiateResult initiate(Long abnormalityId, String filename, String mediaType,
                                         long totalSize, Long chunkSize, String checksumSha256,
                                         AppUser uploader) {
        if (abnormalityId == null) {
            throw AttachmentApiException.unprocessable("异常ID必填");
        }
        InspectionAbnormality ab = abnormalityRepo.findById(abnormalityId)
                .orElseThrow(() -> AttachmentApiException.notFound("巡检异常不存在"));
        String normalizedType = normalizeMediaType(mediaType);
        validateAllowedType(normalizedType);
        String category = categoryOf(normalizedType);
        String safeFilename = sanitizeFilename(filename);
        if (totalSize <= 0) {
            throw AttachmentApiException.unprocessable("文件大小必须大于 0");
        }
        if (totalSize > props.getMaxSizeBytes()) {
            throw AttachmentApiException.unprocessable(
                    "文件超过大小上限 " + props.getMaxSizeBytes() + " 字节");
        }
        long cs = chunkSize == null ? props.getMaxChunkSizeBytes() : chunkSize;
        if (cs <= 0 || cs > props.getMaxChunkSizeBytes()) {
            throw AttachmentApiException.unprocessable(
                    "分片大小非法，允许 1~" + props.getMaxChunkSizeBytes() + " 字节");
        }
        int totalChunks = (int) ((totalSize + cs - 1) / cs);
        if (totalChunks > MAX_TOTAL_CHUNKS) {
            throw AttachmentApiException.unprocessable("分片数量超过上限 " + MAX_TOTAL_CHUNKS);
        }
        String checksum = normalizeChecksum(checksumSha256);

        String key = newKey();
        try {
            storage.createSessionTempDir(key);
        } catch (IOException e) {
            throw new IllegalStateException("创建临时目录失败", e);
        }

        AttachmentUpload upload = new AttachmentUpload();
        upload.setUploadKey(key);
        upload.setOriginalFilename(safeFilename);
        upload.setMediaType(normalizedType);
        upload.setCategory(category);
        upload.setTotalSize(totalSize);
        upload.setChunkSize(cs);
        upload.setTotalChunks(totalChunks);
        upload.setDeclaredChecksum(checksum);
        upload.setAbnormalityId(ab.getId());
        upload.setTaskId(ab.getTaskId());
        upload.setTaskPointId(ab.getTaskPointId());
        upload.setRecordId(ab.getRecordId());
        upload.setEquipmentId(ab.getEquipmentId());
        upload.setUploadedById(uploader != null ? uploader.getId() : null);
        upload.setUploadedByName(uploader != null ? displayName(uploader) : "");
        upload.setTempPath("temp/" + key);
        upload.setStatus(AttachmentUpload.STATUS_UPLOADING);
        upload.setExpiresAt(LocalDateTime.now().plusHours(props.getSessionTtlHours()));
        uploadRepo.save(upload);

        return new UploadInitiateResult(key, safeFilename, normalizedType, category,
                totalSize, cs, totalChunks, checksum, List.of(), upload.getExpiresAt());
    }

    @Transactional
    public ChunkReceiptResult uploadChunk(String uploadKey, int chunkIndex, InputStream body) {
        AttachmentUpload upload = loadUploading(uploadKey);
        int n = upload.getTotalChunks();
        if (chunkIndex < 0 || chunkIndex >= n) {
            throw AttachmentApiException.unprocessable(
                    "分片序号越界，允许 0~" + (n - 1));
        }
        long expected = expectedChunkSize(upload, chunkIndex, n);

        // 先查收据：同序号重传直接幂等返回，不同大小拒绝，避免收据与文件不一致
        Optional<AttachmentChunkReceipt> existing =
                chunkRepo.findByUploadIdAndChunkIndex(upload.getId(), chunkIndex);
        if (existing.isPresent()) {
            if (existing.get().getChunkSize() == expected) {
                return receiptResult(upload, chunkIndex, false);
            }
            throw AttachmentApiException.conflict(
                    "分片 " + chunkIndex + " 已以不同大小上传，请重新发起上传会话");
        }

        long actual;
        try {
            actual = storage.writeChunk(uploadKey, chunkIndex, body);
        } catch (IOException e) {
            throw new IllegalStateException("分片写入失败", e);
        }
        if (actual != expected) {
            safeDeleteChunk(uploadKey, chunkIndex);
            throw AttachmentApiException.unprocessable(
                    "分片 " + chunkIndex + " 大小不合法，期望 " + expected + " 字节，实际 " + actual + " 字节");
        }

        try {
            chunkRepo.saveAndFlush(new AttachmentChunkReceipt(upload.getId(), chunkIndex, actual));
        } catch (org.springframework.dao.DataIntegrityViolationException dup) {
            // 并发首传同一分片：约束冲突直接拒绝，客户端随后幂等重传即可
            throw AttachmentApiException.conflict("分片 " + chunkIndex + " 正在被并发上传，请重试");
        }
        return receiptResult(upload, chunkIndex, false);
    }

    @Transactional(readOnly = true)
    public UploadInitiateResult getUploadStatus(String uploadKey) {
        AttachmentUpload upload = uploadRepo.findByUploadKey(uploadKey)
                .orElseThrow(() -> AttachmentApiException.notFound("上传会话不存在"));
        List<Integer> received = receivedChunks(upload);
        return new UploadInitiateResult(upload.getUploadKey(), upload.getOriginalFilename(),
                upload.getMediaType(), upload.getCategory(), upload.getTotalSize(),
                upload.getChunkSize(), upload.getTotalChunks(), upload.getDeclaredChecksum(),
                received, upload.getExpiresAt());
    }

    /**
     * 完成上传：行级悲观锁串行化重复/并发完成。
     * 校验（大小 + SHA-256 + 允许类型）全部通过才创建证据并绑定异常。
     */
    @Transactional
    public AttachmentView complete(String uploadKey) {
        AttachmentUpload upload = uploadRepo.findByUploadKeyForUpdate(uploadKey)
                .orElseThrow(() -> AttachmentApiException.notFound("上传会话不存在"));

        if (AttachmentUpload.STATUS_COMPLETED.equals(upload.getStatus()) && upload.getAttachmentId() != null) {
            EvidenceAttachment existing = attachmentRepo.findById(upload.getAttachmentId())
                    .orElseThrow(() -> AttachmentApiException.conflict("会话已完成但证据记录缺失，请联系管理员"));
            return toView(existing, false, false);
        }
        if (!AttachmentUpload.STATUS_UPLOADING.equals(upload.getStatus())) {
            throw AttachmentApiException.conflict("上传会话状态为 " + upload.getStatus() + "，无法完成");
        }
        if (upload.getExpiresAt().isBefore(LocalDateTime.now())) {
            upload.setStatus(AttachmentUpload.STATUS_EXPIRED);
            uploadRepo.save(upload);
            throw AttachmentApiException.conflict("上传会话已过期，请重新发起");
        }

        int n = upload.getTotalChunks();
        List<AttachmentChunkReceipt> receipts = chunkRepo.findByUploadIdForUpdate(upload.getId());
        if (receipts.size() != n) {
            throw AttachmentApiException.unprocessable(
                    "分片不完整，已接收 " + receipts.size() + "/" + n + "，缺失 "
                            + missingChunks(upload, receipts));
        }
        for (AttachmentChunkReceipt r : receipts) {
            long expected = expectedChunkSize(upload, r.getChunkIndex(), n);
            if (r.getChunkSize() != expected) {
                throw AttachmentApiException.unprocessable(
                        "分片 " + r.getChunkIndex() + " 登记大小与期望不符");
            }
        }

        // 完成前再次确认允许类型（配置可能在会话期间收紧）
        validateAllowedType(upload.getMediaType());

        String storedName = upload.getUploadKey()
                + EXT_BY_TYPE.getOrDefault(upload.getMediaType(), ".bin");
        AttachmentStorage.AssembledFile assembled;
        try {
            assembled = storage.assemble(uploadKey, n, upload.getTotalSize(),
                    upload.getDeclaredChecksum(), storedName);
        } catch (AttachmentStorage.ChecksumMismatchException e) {
            // 保留会话与已传分片，客户端可重传问题分片后再次完成
            throw AttachmentApiException.unprocessable("校验和不匹配，绑定失败：" + e.getMessage());
        } catch (AttachmentStorage.SizeMismatchException e) {
            throw AttachmentApiException.unprocessable("声明大小不匹配，绑定失败：" + e.getMessage());
        } catch (IOException e) {
            throw new IllegalStateException("拼装文件失败", e);
        }

        InspectionAbnormality ab = abnormalityRepo.findById(upload.getAbnormalityId())
                .orElseThrow(() -> {
                    safeDeleteAssembled(assembled.relativePath());
                    return AttachmentApiException.notFound("绑定的巡检异常已不存在");
                });

        EvidenceAttachment att = new EvidenceAttachment();
        att.setAbnormalityId(ab.getId());
        att.setTaskId(ab.getTaskId());
        att.setTaskPointId(ab.getTaskPointId());
        att.setRecordId(ab.getRecordId());
        att.setEquipmentId(ab.getEquipmentId());
        // 异常若已转工单，证据同步挂到工单引用上（仅引用，不复制文件）
        att.setWorkOrderId(ab.getWorkOrderId());
        att.setOriginalFilename(upload.getOriginalFilename());
        att.setStoragePath(assembled.relativePath());
        att.setMediaType(upload.getMediaType());
        att.setCategory(upload.getCategory());
        att.setFileSize(assembled.size());
        att.setChecksumSha256(assembled.sha256());
        att.setUploadedById(upload.getUploadedById());
        att.setUploadedByName(upload.getUploadedByName());
        att.setIntegrityStatus(EvidenceAttachment.STATUS_OK);
        att.setVerifiedAt(LocalDateTime.now());
        att = attachmentRepo.save(att);

        upload.setStatus(AttachmentUpload.STATUS_COMPLETED);
        upload.setAttachmentId(att.getId());
        upload.setCompletedAt(LocalDateTime.now());
        uploadRepo.save(upload);
        chunkRepo.deleteByUploadId(upload.getId());

        String key = uploadKey;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    storage.deleteSessionTemp(key);
                } catch (IOException e) {
                    log.warn("提交后清理临时分片失败 key={}: {}", key, e.getMessage());
                }
            }
        });
        return toView(att, false, false);
    }

    @Transactional
    public void abort(String uploadKey) {
        AttachmentUpload upload = uploadRepo.findByUploadKeyForUpdate(uploadKey)
                .orElseThrow(() -> AttachmentApiException.notFound("上传会话不存在"));
        if (AttachmentUpload.STATUS_COMPLETED.equals(upload.getStatus())) {
            throw AttachmentApiException.conflict("上传已完成，不能中止");
        }
        upload.setStatus(AttachmentUpload.STATUS_ABORTED);
        uploadRepo.save(upload);
        chunkRepo.deleteByUploadId(upload.getId());
        registerTempCleanup(uploadKey);
    }

    // ------------------------------------------------------------------
    // 查询 / 完整性 / 下载 / 删除
    // ------------------------------------------------------------------

    @Transactional
    public List<AttachmentView> listByAbnormality(Long abnormalityId, boolean forceHash) {
        if (!abnormalityRepo.existsById(abnormalityId)) {
            throw AttachmentApiException.notFound("巡检异常不存在");
        }
        List<EvidenceAttachment> list = attachmentRepo.findByAbnormalityIdOrderByCreatedAtDesc(abnormalityId);
        List<AttachmentView> views = new ArrayList<>();
        for (EvidenceAttachment a : list) views.add(toView(a, forceHash, true));
        return views;
    }

    @Transactional(readOnly = true)
    public List<AttachmentView> listByWorkOrder(Long workOrderId) {
        List<EvidenceAttachment> list = attachmentRepo.findByWorkOrderIdOrderByCreatedAtDesc(workOrderId);
        List<AttachmentView> views = new ArrayList<>();
        for (EvidenceAttachment a : list) views.add(toView(a, false, false));
        return views;
    }

    @Transactional
    public AttachmentView verify(Long id) {
        EvidenceAttachment att = attachmentRepo.findById(id)
                .orElseThrow(() -> AttachmentApiException.notFound("附件不存在"));
        return toView(att, true, true);
    }

    /** 单份证据状态（存在性/大小探测，不重算哈希）。 */
    @Transactional
    public AttachmentView statusOf(Long id) {
        EvidenceAttachment att = attachmentRepo.findById(id)
                .orElseThrow(() -> AttachmentApiException.notFound("附件不存在"));
        return toView(att, false, true);
    }

    /** 下载前的受控解析：鉴权由过滤器统一完成，路径解析在存储层防穿越。 */
    @Transactional(readOnly = true)
    public DownloadTarget openDownload(Long id) {
        EvidenceAttachment att = attachmentRepo.findById(id)
                .orElseThrow(() -> AttachmentApiException.notFound("附件不存在"));
        Path path;
        try {
            path = storage.resolveSafely(att.getStoragePath());
        } catch (SecurityException e) {
            // 元数据中的路径非法（防御性，正常不会发生）
            log.error("附件 {} 存储路径非法: {}", id, att.getStoragePath());
            throw AttachmentApiException.gone("证据文件路径不可用");
        }
        boolean exists = java.nio.file.Files.isRegularFile(path);
        return new DownloadTarget(att, path, exists);
    }

    /**
     * 删除附件：只有未被任何异常/工单引用的证据允许删除。
     * 数据库删除与文件删除以“先提交、后删文件”划定边界，避免悬空引用。
     */
    @Transactional
    public void delete(Long id) {
        EvidenceAttachment att = attachmentRepo.findByIdForUpdate(id)
                .orElseThrow(() -> AttachmentApiException.notFound("附件不存在"));
        if (att.getAbnormalityId() != null || att.getWorkOrderId() != null) {
            throw AttachmentApiException.conflict(
                    "证据已被异常" + (att.getAbnormalityId() != null ? "(#" + att.getAbnormalityId() + ")" : "")
                            + (att.getWorkOrderId() != null ? "/工单(#" + att.getWorkOrderId() + ")" : "")
                            + "引用，禁止删除");
        }
        String path = att.getStoragePath();
        attachmentRepo.delete(att);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    storage.deleteFile(path);
                } catch (IOException e) {
                    log.warn("未引用附件物理文件删除失败 path={}: {}", path, e.getMessage());
                }
            }
        });
    }

    /**
     * 异常转工单时保留引用：只回填 workOrderId，不复制物理文件。
     * 与转单操作处于同一事务。
     */
    @Transactional
    public int linkToWorkOrder(Long abnormalityId, Long workOrderId) {
        List<EvidenceAttachment> list = attachmentRepo.findByAbnormalityIdForUpdate(abnormalityId);
        for (EvidenceAttachment a : list) {
            a.setWorkOrderId(workOrderId);
        }
        attachmentRepo.saveAll(list);
        return list.size();
    }

    // ------------------------------------------------------------------
    // 清理：过期/失败会话、孤儿物理文件
    // ------------------------------------------------------------------

    /** 定时入口：只清理过期的上传会话临时分片。 */
    public CleanupResult cleanupExpiredSessions() {
        Integer expiredCount = txTemplate.execute(status -> {
            List<AttachmentUpload> expired = uploadRepo.findByStatusAndExpiresAtBefore(
                    AttachmentUpload.STATUS_UPLOADING, LocalDateTime.now());
            for (AttachmentUpload u : expired) {
                u.setStatus(AttachmentUpload.STATUS_EXPIRED);
                chunkRepo.deleteByUploadId(u.getId());
            }
            uploadRepo.saveAll(expired);
            return expired.size();
        });
        int expired = expiredCount == null ? 0 : expiredCount;

        // 已标记过期/中止的会话，其临时分片在事务外逐个删除
        List<String> staleKeys = txTemplate.execute(status -> {
            List<String> keys = new ArrayList<>();
            for (AttachmentUpload u : uploadRepo.findByStatusOrderByCreatedAtDesc(AttachmentUpload.STATUS_EXPIRED)) {
                keys.add(u.getUploadKey());
            }
            for (AttachmentUpload u : uploadRepo.findByStatusOrderByCreatedAtDesc(AttachmentUpload.STATUS_ABORTED)) {
                keys.add(u.getUploadKey());
            }
            return keys;
        });
        if (staleKeys != null) {
            for (String key : staleKeys) {
                try {
                    if (storage.resolveSessionTempDir(key).toFile().exists()) {
                        storage.deleteSessionTemp(key);
                    }
                } catch (IOException e) {
                    log.warn("清理过期会话分片失败 key={}: {}", key, e.getMessage());
                }
            }
        }

        int orphanDirs = 0;
        try {
            Set<String> liveKeys = new HashSet<>(txTemplate.execute(status -> {
                Set<String> keys = new HashSet<>();
                for (AttachmentUpload u : uploadRepo.findByStatusOrderByCreatedAtDesc(AttachmentUpload.STATUS_UPLOADING)) {
                    keys.add(u.getUploadKey());
                }
                return keys;
            }));
            for (Path dir : storage.listSessionDirs()) {
                String name = dir.getFileName().toString();
                if (!liveKeys.contains(name)) {
                    storage.deleteSessionTemp(name);
                    orphanDirs++;
                }
            }
        } catch (Exception e) {
            log.warn("扫描孤儿临时目录失败: {}", e.getMessage());
        }
        return new CleanupResult(expired, orphanDirs, 0);
    }

    /** 全量维护：过期会话 + 证据目录中无任何元数据引用的孤儿文件。 */
    public CleanupResult garbageCollect() {
        CleanupResult sessions = cleanupExpiredSessions();
        Set<String> referenced = txTemplate.execute(status -> {
            Set<String> paths = new HashSet<>();
            for (EvidenceAttachment a : attachmentRepo.findAll()) {
                if (a.getStoragePath() != null) paths.add(a.getStoragePath());
            }
            return paths;
        });
        if (referenced == null) referenced = Set.of();
        int orphanFiles = 0;
        try {
            for (String rel : storage.listEvidenceFiles().keySet()) {
                if (!referenced.contains(rel)) {
                    storage.deleteFile(rel);
                    orphanFiles++;
                }
            }
        } catch (IOException e) {
            log.warn("清理孤儿证据文件失败: {}", e.getMessage());
        }
        return new CleanupResult(sessions.expiredSessions(), sessions.orphanTempDirs(), orphanFiles);
    }

    public record CleanupResult(int expiredSessions, int orphanTempDirs, int orphanEvidenceFiles) {}

    public record DownloadTarget(EvidenceAttachment attachment, Path path, boolean contentAvailable) {}

    // ------------------------------------------------------------------
    // 内部辅助
    // ------------------------------------------------------------------

    private AttachmentUpload loadUploading(String uploadKey) {
        AttachmentUpload upload = uploadRepo.findByUploadKey(uploadKey)
                .orElseThrow(() -> AttachmentApiException.notFound("上传会话不存在"));
        if (!AttachmentUpload.STATUS_UPLOADING.equals(upload.getStatus())) {
            throw AttachmentApiException.conflict("上传会话状态为 " + upload.getStatus() + "，不能继续上传分片");
        }
        if (upload.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw AttachmentApiException.conflict("上传会话已过期，请重新发起");
        }
        return upload;
    }

    private ChunkReceiptResult receiptResult(AttachmentUpload upload, int chunkIndex, boolean replaced) {
        List<Integer> received = receivedChunks(upload);
        return new ChunkReceiptResult(upload.getUploadKey(), chunkIndex,
                expectedChunkSize(upload, chunkIndex, upload.getTotalChunks()),
                replaced, received.size(), upload.getTotalChunks(),
                received, missingFromReceived(upload, received),
                received.size() == upload.getTotalChunks());
    }

    private List<Integer> receivedChunks(AttachmentUpload upload) {
        List<AttachmentChunkReceipt> receipts = chunkRepo.findByUploadIdOrderByChunkIndexAsc(upload.getId());
        List<Integer> idx = new ArrayList<>(receipts.size());
        for (AttachmentChunkReceipt r : receipts) idx.add(r.getChunkIndex());
        return idx;
    }

    private List<Integer> missingChunks(AttachmentUpload upload, List<AttachmentChunkReceipt> receipts) {
        boolean[] seen = new boolean[upload.getTotalChunks()];
        for (AttachmentChunkReceipt r : receipts) seen[r.getChunkIndex()] = true;
        List<Integer> missing = new ArrayList<>();
        for (int i = 0; i < seen.length; i++) if (!seen[i]) missing.add(i);
        return missing;
    }

    private List<Integer> missingFromReceived(AttachmentUpload upload, List<Integer> received) {
        boolean[] seen = new boolean[upload.getTotalChunks()];
        received.forEach(i -> seen[i] = true);
        List<Integer> missing = new ArrayList<>();
        for (int i = 0; i < seen.length; i++) if (!seen[i]) missing.add(i);
        return missing;
    }

    private long expectedChunkSize(AttachmentUpload upload, int chunkIndex, int totalChunks) {
        if (chunkIndex < totalChunks - 1) return upload.getChunkSize();
        long base = (long) (totalChunks - 1) * upload.getChunkSize();
        return upload.getTotalSize() - base;
    }

    private void registerTempCleanup(String uploadKey) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    storage.deleteSessionTemp(uploadKey);
                } catch (IOException e) {
                    log.warn("提交后清理临时分片失败 key={}: {}", uploadKey, e.getMessage());
                }
            }
        });
    }

    private void safeDeleteChunk(String key, int index) {
        try {
            Path dir = storage.resolveSessionTempDir(key);
            java.nio.file.Files.deleteIfExists(dir.resolve("chunk-" + index + ".part"));
        } catch (Exception ignored) {
            // 尽力清理
        }
    }

    private void safeDeleteAssembled(String relativePath) {
        try {
            storage.deleteFile(relativePath);
        } catch (IOException ignored) {
            // 异常已不存在场景下的回滚清理，尽力而为
        }
    }

    /**
     * 构造证据视图并执行完整性探测：默认查存在性与大小；forceHash 时重算 SHA-256。
     * 数据库记录存在但文件丢失时返回降级状态（contentAvailable=false），不抛 500。
     */
    AttachmentView toView(EvidenceAttachment a, boolean forceHash, boolean persistStatus) {
        String status = a.getIntegrityStatus();
        String detail = "校验通过";
        boolean available;
        try {
            Path p = storage.resolveSafely(a.getStoragePath());
            if (!java.nio.file.Files.isRegularFile(p)) {
                status = EvidenceAttachment.STATUS_MISSING;
                detail = "物理文件已丢失，仅保留元数据";
                available = false;
            } else {
                long size = java.nio.file.Files.size(p);
                if (size != a.getFileSize()) {
                    status = EvidenceAttachment.STATUS_SIZE_MISMATCH;
                    detail = "文件大小与登记不一致，登记 " + a.getFileSize() + "，实际 " + size;
                    available = false;
                } else if (forceHash) {
                    String actual = storage.sha256OfExistingFile(a.getStoragePath());
                    if (!actual.equalsIgnoreCase(a.getChecksumSha256())) {
                        status = EvidenceAttachment.STATUS_CORRUPT;
                        detail = "SHA-256 校验失败，证据可能已被篡改或损坏";
                        available = false;
                    } else {
                        status = EvidenceAttachment.STATUS_OK;
                        detail = "SHA-256 复核通过";
                        available = true;
                    }
                } else {
                    status = EvidenceAttachment.STATUS_OK;
                    detail = "文件存在，大小一致";
                    available = true;
                }
            }
        } catch (SecurityException e) {
            status = EvidenceAttachment.STATUS_MISSING;
            detail = "存储路径非法：" + e.getMessage();
            available = false;
        } catch (IOException e) {
            status = EvidenceAttachment.STATUS_MISSING;
            detail = "文件不可读：" + e.getMessage();
            available = false;
        }

        if (persistStatus && !status.equals(a.getIntegrityStatus())) {
            a.setIntegrityStatus(status);
            a.setVerifiedAt(LocalDateTime.now());
            attachmentRepo.save(a);
        }
        return new AttachmentView(a.getId(), a.getAbnormalityId(), a.getTaskId(), a.getTaskPointId(),
                a.getRecordId(), a.getEquipmentId(), a.getWorkOrderId(), a.getOriginalFilename(),
                a.getMediaType(), a.getCategory(), a.getFileSize(), a.getChecksumSha256(),
                a.getUploadedById(), a.getUploadedByName(), status, detail, available,
                a.getVerifiedAt(), a.getCreatedAt());
    }

    private String newKey() {
        byte[] b = new byte[24];
        random.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    private void validateAllowedType(String mediaType) {
        if (!props.getAllowedTypes().contains(mediaType)) {
            throw AttachmentApiException.unprocessable("不支持的媒体类型：" + mediaType);
        }
    }

    private String normalizeMediaType(String mediaType) {
        if (mediaType == null || mediaType.isBlank()) {
            throw AttachmentApiException.unprocessable("媒体类型必填");
        }
        String t = mediaType.trim().toLowerCase(Locale.ROOT);
        int semi = t.indexOf(';');
        if (semi >= 0) t = t.substring(0, semi).trim();
        if (!t.matches("[a-z0-9][a-z0-9.+-]*/[a-z0-9][a-z0-9.+-]*")) {
            throw AttachmentApiException.unprocessable("媒体类型格式非法");
        }
        return t;
    }

    private String categoryOf(String mediaType) {
        for (Map.Entry<String, String> e : CATEGORY_BY_PREFIX.entrySet()) {
            if (mediaType.startsWith(e.getKey())) return e.getValue();
        }
        if (DOCUMENT_TYPES.contains(mediaType)) return "document";
        throw AttachmentApiException.unprocessable("不支持的媒体类型：" + mediaType);
    }

    private String normalizeChecksum(String checksum) {
        if (checksum == null || !checksum.trim().matches("[0-9a-fA-F]{64}")) {
            throw AttachmentApiException.unprocessable("checksumSha256 必须为 64 位十六进制");
        }
        return checksum.trim().toLowerCase(Locale.ROOT);
    }

    private String sanitizeFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            throw AttachmentApiException.unprocessable("文件名必填");
        }
        String name = filename.replace('\\', '/');
        if (name.contains("/")) name = name.substring(name.lastIndexOf('/') + 1);
        name = name.replaceAll("[\\r\\n\\t]", "").trim();
        if (name.isEmpty() || name.equals(".") || name.equals("..") || name.length() > 255) {
            throw AttachmentApiException.unprocessable("文件名非法");
        }
        return name;
    }

    private String displayName(AppUser user) {
        String d = user.getDisplayName();
        return d != null && !d.isBlank() ? d : user.getUsername();
    }
}
