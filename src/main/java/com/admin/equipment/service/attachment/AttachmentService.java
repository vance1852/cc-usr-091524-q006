package com.admin.equipment.service.attachment;

import com.admin.equipment.model.attachment.Attachment;
import com.admin.equipment.model.attachment.AttachmentLink;
import com.admin.equipment.repo.attachment.AttachmentLinkRepository;
import com.admin.equipment.repo.attachment.AttachmentRepository;
import com.admin.equipment.repo.inspection.InspectionAbnormalityRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 受控附件业务层：分段上传会话、完成确认（校验和/大小/类型三重核验）、
 * 业务绑定、引用保留、删除保护、过期清理与完整性检查。
 */
@Service
public class AttachmentService {

    private static final Logger log = LoggerFactory.getLogger(AttachmentService.class);
    private static final Pattern SHA256_PATTERN = Pattern.compile("[0-9a-fA-F]{64}");
    private static final int MAX_CHUNKS = 10000;

    /** 附件不存在 */
    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String message) { super(message); }
    }

    /** 状态或校验不合法（对应 422） */
    public static class StateException extends RuntimeException {
        public StateException(String message) { super(message); }
    }

    /** 证据仍被业务引用，禁止删除（对应 409） */
    public static class ReferencedException extends RuntimeException {
        private final long references;
        public ReferencedException(long references) {
            super("附件仍被 " + references + " 处业务引用，禁止删除证据");
            this.references = references;
        }
        public long getReferences() { return references; }
    }

    private final AttachmentRepository attachmentRepo;
    private final AttachmentLinkRepository linkRepo;
    private final InspectionAbnormalityRepository abnormalityRepo;
    private final AttachmentStorageService storage;
    private final AttachmentProperties props;
    private final TransactionTemplate txTemplate;

    public AttachmentService(AttachmentRepository attachmentRepo,
                             AttachmentLinkRepository linkRepo,
                             InspectionAbnormalityRepository abnormalityRepo,
                             AttachmentStorageService storage,
                             AttachmentProperties props,
                             org.springframework.transaction.PlatformTransactionManager txManager) {
        this.attachmentRepo = attachmentRepo;
        this.linkRepo = linkRepo;
        this.abnormalityRepo = abnormalityRepo;
        this.storage = storage;
        this.props = props;
        this.txTemplate = new TransactionTemplate(txManager);
    }

    // ------------------------------------------------------------------
    // 分段上传会话
    // ------------------------------------------------------------------

    /** 发起上传会话：校验声明的大小、SHA-256 与媒体类型白名单。 */
    @Transactional
    public Attachment createSession(String originalName, Long sizeBytes, String sha256,
                                    String mediaType, Long uploaderId, String uploaderName) {
        if (originalName == null || originalName.isBlank()) {
            throw new StateException("文件名必填");
        }
        if (sizeBytes == null || sizeBytes <= 0) {
            throw new StateException("文件大小必须为正数");
        }
        if (sizeBytes > props.getMaxSizeBytes()) {
            throw new StateException("文件大小超出限制 " + props.getMaxSizeBytes() + " 字节");
        }
        if (sha256 == null || !SHA256_PATTERN.matcher(sha256).matches()) {
            throw new StateException("SHA-256 必须是 64 位十六进制字符串");
        }
        if (!props.isMediaTypeAllowed(mediaType)) {
            throw new StateException("不允许的媒体类型: " + mediaType);
        }
        Attachment a = new Attachment();
        a.setStorageKey(UUID.randomUUID().toString().replace("-", ""));
        // 文件名仅作元数据展示，绝不参与落盘路径
        a.setOriginalName(originalName.replace("/", "_").replace("\\", "_"));
        a.setMediaType(mediaType.trim());
        a.setCategory(AttachmentProperties.categoryOf(mediaType));
        a.setSizeBytes(sizeBytes);
        a.setSha256(sha256.toLowerCase());
        a.setStatus(Attachment.STATUS_UPLOADING);
        a.setUploadedBy(uploaderId);
        a.setUploadedByName(uploaderName == null ? "" : uploaderName);
        a.setExpiresAt(LocalDateTime.now().plusHours(props.getSessionExpireHours()));
        return attachmentRepo.save(a);
    }

    public record SessionView(Attachment attachment, List<Integer> uploadedChunks, long chunkSizeBytes) {}

    /** 会话状态（含已上传分片序号），客户端据此断点续传。 */
    @Transactional(readOnly = true)
    public SessionView getSession(Long id) {
        Attachment a = attachmentRepo.findById(id)
                .orElseThrow(() -> new NotFoundException("上传会话不存在"));
        return new SessionView(a, storage.listChunkIndexes(id), props.getChunkSizeBytes());
    }

    /** 写入一个分片；同序号重复上传直接覆盖，保证断点续传幂等。 */
    public long uploadChunk(Long id, int index, InputStream in) {
        Attachment a = attachmentRepo.findById(id)
                .orElseThrow(() -> new NotFoundException("上传会话不存在"));
        if (!Attachment.STATUS_UPLOADING.equals(a.getStatus())) {
            throw new StateException("会话状态为 " + a.getStatus() + "，不能继续上传分片");
        }
        if (isExpired(a)) {
            markExpired(a);
            throw new StateException("上传会话已过期");
        }
        long maxChunks = Math.max(1, (a.getSizeBytes() + props.getChunkSizeBytes() - 1) / props.getChunkSizeBytes());
        if (index < 0 || index >= Math.min(maxChunks, MAX_CHUNKS)) {
            throw new StateException("分片序号超出范围，允许 0~" + (Math.min(maxChunks, MAX_CHUNKS) - 1));
        }
        try {
            return storage.writeChunk(id, index, in, props.getChunkSizeBytes());
        } catch (IOException e) {
            throw new StateException("分片写入失败: " + e.getMessage());
        }
    }

    /**
     * 完成确认：拼装分片并核验 SHA-256 与声明大小。
     * 通过悲观锁串行化，重复完成幂等返回；校验失败时状态落库为 failed
     * （临时分片留待清理），并向调用方返回明确的错误。
     */
    public Attachment complete(Long id) {
        CompleteOutcome outcome = txTemplate.execute(status -> doComplete(id));
        if (outcome == null) {
            throw new NotFoundException("上传会话不存在");
        }
        if (outcome.error() != null) {
            throw new StateException(outcome.error());
        }
        return outcome.attachment();
    }

    private record CompleteOutcome(Attachment attachment, String error) {
        static CompleteOutcome ok(Attachment a) { return new CompleteOutcome(a, null); }
        static CompleteOutcome error(String message) { return new CompleteOutcome(null, message); }
    }

    private CompleteOutcome doComplete(Long id) {
        Attachment a = attachmentRepo.findByIdForUpdate(id)
                .orElseThrow(() -> new NotFoundException("上传会话不存在"));
        if (Attachment.STATUS_COMPLETED.equals(a.getStatus())) {
            return CompleteOutcome.ok(a); // 重复完成：幂等返回
        }
        if (Attachment.STATUS_FAILED.equals(a.getStatus())) {
            return CompleteOutcome.error("上传已失败（" + a.getFailReason() + "），请重新发起会话");
        }
        if (Attachment.STATUS_EXPIRED.equals(a.getStatus()) || isExpired(a)) {
            a.setStatus(Attachment.STATUS_EXPIRED);
            attachmentRepo.save(a);
            return CompleteOutcome.error("上传会话已过期");
        }

        AttachmentStorageService.AssembleResult assembled;
        try {
            assembled = storage.assemble(id);
        } catch (IOException e) {
            return CompleteOutcome.error("分片不完整: " + e.getMessage());
        }

        if (assembled.totalSize() != a.getSizeBytes()) {
            return CompleteOutcome.error(failSession(a, "声明大小不匹配，声明 " + a.getSizeBytes()
                    + " 字节，实际 " + assembled.totalSize() + " 字节"));
        }
        if (!assembled.sha256Hex().equalsIgnoreCase(a.getSha256())) {
            return CompleteOutcome.error(failSession(a, "SHA-256 校验和不匹配"));
        }

        try {
            storage.moveToFinal(assembled.assembledFile(), a.getStorageKey());
        } catch (IOException e) {
            return CompleteOutcome.error("文件落盘失败: " + e.getMessage());
        }
        storage.deleteTempDirQuietly(id);
        a.setStatus(Attachment.STATUS_COMPLETED);
        a.setCompletedAt(LocalDateTime.now());
        return CompleteOutcome.ok(attachmentRepo.save(a));
    }

    /** 标记失败并落库，返回失败原因。 */
    private String failSession(Attachment a, String reason) {
        a.setStatus(Attachment.STATUS_FAILED);
        a.setFailReason(reason.length() > 250 ? reason.substring(0, 250) : reason);
        storage.deleteAssembledTmpQuietly(a.getId());
        // 临时分片保留在磁盘上，由清理任务统一回收
        attachmentRepo.save(a);
        return a.getFailReason();
    }

    // ------------------------------------------------------------------
    // 业务绑定与引用
    // ------------------------------------------------------------------

    /**
     * 绑定附件到巡检异常。只有已完成、且文件校验和/声明大小/媒体类型
     * 全部核验通过的附件才允许绑定。并发绑定同一附件到同一异常幂等。
     */
    public AttachmentLink bindToAbnormality(Long attachmentId, Long abnormalityId, String operator) {
        Attachment a = attachmentRepo.findById(attachmentId)
                .orElseThrow(() -> new NotFoundException("附件不存在"));
        if (!abnormalityRepo.existsById(abnormalityId)) {
            throw new NotFoundException("巡检异常不存在");
        }
        verifyBindable(a);

        var existing = linkRepo.findByAttachmentIdAndBizTypeAndBizId(
                attachmentId, AttachmentLink.BIZ_ABNORMALITY, abnormalityId);
        if (existing.isPresent()) {
            return existing.get(); // 已绑定：幂等返回
        }
        try {
            // 独立小事务插入，依靠唯一约束兜底并发竞争
            return txTemplate.execute(status -> linkRepo.saveAndFlush(
                    new AttachmentLink(attachmentId, AttachmentLink.BIZ_ABNORMALITY, abnormalityId, operator)));
        } catch (DataIntegrityViolationException e) {
            // 并发绑定竞争失败：对方已插入，返回既有引用
            return linkRepo.findByAttachmentIdAndBizTypeAndBizId(
                            attachmentId, AttachmentLink.BIZ_ABNORMALITY, abnormalityId)
                    .orElseThrow(() -> e);
        }
    }

    /** 绑定前的三重核验：状态完成、类型白名单、文件校验和与声明大小一致。 */
    private void verifyBindable(Attachment a) {
        if (!Attachment.STATUS_COMPLETED.equals(a.getStatus())) {
            throw new StateException("附件未完成上传，不能绑定");
        }
        if (!props.isMediaTypeAllowed(a.getMediaType())) {
            throw new StateException("附件媒体类型不在允许范围: " + a.getMediaType());
        }
        if (!storage.finalFileExists(a.getStorageKey())) {
            throw new StateException("附件文件已丢失，不能绑定");
        }
        try {
            if (storage.sizeOfFinal(a.getStorageKey()) != a.getSizeBytes()) {
                throw new StateException("附件文件大小与元数据不一致，不能绑定");
            }
            String actual = storage.sha256OfFinal(a.getStorageKey());
            if (!actual.equalsIgnoreCase(a.getSha256())) {
                throw new StateException("附件文件校验和与元数据不一致，不能绑定");
            }
        } catch (IOException e) {
            throw new StateException("附件文件读取失败: " + e.getMessage());
        }
    }

    /**
     * 异常转工单时保留附件引用：为异常下的全部附件追加工单引用，
     * 只插入指针记录，不复制文件。加入调用方事务，与工单创建同生共死。
     * @return 被引用的附件 ID 列表
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public List<Long> linkAbnormalityAttachmentsToWorkOrder(Long abnormalityId, Long workOrderId, String operator) {
        List<AttachmentLink> abLinks = linkRepo.findByBizTypeAndBizId(
                AttachmentLink.BIZ_ABNORMALITY, abnormalityId);
        List<Long> attachmentIds = new ArrayList<>();
        for (AttachmentLink abLink : abLinks) {
            Long attId = abLink.getAttachmentId();
            if (!linkRepo.existsByAttachmentIdAndBizTypeAndBizId(
                    attId, AttachmentLink.BIZ_WORK_ORDER, workOrderId)) {
                linkRepo.save(new AttachmentLink(attId, AttachmentLink.BIZ_WORK_ORDER, workOrderId, operator));
            }
            attachmentIds.add(attId);
        }
        return attachmentIds;
    }

    /** 解除异常与附件的引用（不删除文件本身），幂等。 */
    @Transactional
    public void unbindFromAbnormality(Long attachmentId, Long abnormalityId) {
        linkRepo.deleteByAttachmentIdAndBizTypeAndBizId(
                attachmentId, AttachmentLink.BIZ_ABNORMALITY, abnormalityId);
    }

    // ------------------------------------------------------------------
    // 证据视图（完整性状态 + 来源）
    // ------------------------------------------------------------------

    public record LinkView(Long linkId, String bizType, Long bizId, String createdBy, LocalDateTime createdAt) {}

    public record EvidenceView(Attachment attachment, String integrity, List<LinkView> references) {}

    /** 异常闭环记录的证据列表：每份证据带完整性状态与全部业务来源。 */
    @Transactional(readOnly = true)
    public List<EvidenceView> listEvidenceForAbnormality(Long abnormalityId) {
        return buildEvidenceViews(linkRepo.findByBizTypeAndBizId(AttachmentLink.BIZ_ABNORMALITY, abnormalityId));
    }

    /** 工单引用的证据列表。 */
    @Transactional(readOnly = true)
    public List<EvidenceView> listEvidenceForWorkOrder(Long workOrderId) {
        return buildEvidenceViews(linkRepo.findByBizTypeAndBizId(AttachmentLink.BIZ_WORK_ORDER, workOrderId));
    }

    private List<EvidenceView> buildEvidenceViews(List<AttachmentLink> links) {
        List<EvidenceView> views = new ArrayList<>();
        for (AttachmentLink link : links) {
            Attachment a = attachmentRepo.findById(link.getAttachmentId()).orElse(null);
            if (a == null) continue;
            List<LinkView> refs = new ArrayList<>();
            for (AttachmentLink l : linkRepo.findByAttachmentId(a.getId())) {
                refs.add(new LinkView(l.getId(), l.getBizType(), l.getBizId(), l.getCreatedBy(), l.getCreatedAt()));
            }
            views.add(new EvidenceView(a, integrityOf(a), refs));
        }
        return views;
    }

    /** 轻量完整性状态：ok / missing / size_mismatch / invalid_key / 会话状态。 */
    public String integrityOf(Attachment a) {
        if (!Attachment.STATUS_COMPLETED.equals(a.getStatus())) {
            return a.getStatus();
        }
        try {
            if (!storage.finalFileExists(a.getStorageKey())) return "missing";
            if (storage.sizeOfFinal(a.getStorageKey()) != a.getSizeBytes()) return "size_mismatch";
            return "ok";
        } catch (IllegalArgumentException e) {
            return "invalid_key";
        } catch (IOException e) {
            return "unreadable";
        }
    }

    public record VerifyResult(String integrity, String expectedSha256, String actualSha256) {}

    /** 重量完整性校验：重算文件 SHA-256 与元数据比对。 */
    @Transactional(readOnly = true)
    public VerifyResult verifyIntegrity(Long id) {
        Attachment a = attachmentRepo.findById(id)
                .orElseThrow(() -> new NotFoundException("附件不存在"));
        if (!Attachment.STATUS_COMPLETED.equals(a.getStatus())) {
            return new VerifyResult(a.getStatus(), a.getSha256(), null);
        }
        if (!storage.finalFileExists(a.getStorageKey())) {
            return new VerifyResult("missing", a.getSha256(), null);
        }
        try {
            String actual = storage.sha256OfFinal(a.getStorageKey());
            boolean sizeOk = storage.sizeOfFinal(a.getStorageKey()) == a.getSizeBytes();
            String integrity = actual.equalsIgnoreCase(a.getSha256()) && sizeOk ? "ok" : "checksum_mismatch";
            return new VerifyResult(integrity, a.getSha256(), actual);
        } catch (IOException e) {
            return new VerifyResult("unreadable", a.getSha256(), null);
        }
    }

    // ------------------------------------------------------------------
    // 下载与删除
    // ------------------------------------------------------------------

    public Attachment getRequired(Long id) {
        return attachmentRepo.findById(id)
                .orElseThrow(() -> new NotFoundException("附件不存在"));
    }

    /**
     * 删除未引用附件。事务边界：行锁 + 引用计数检查 + 删除元数据在同一事务内；
     * 物理文件在事务提交成功后删除，避免"库已删、文件残留"或"文件已删、库回滚"。
     */
    @Transactional
    public void deleteIfUnreferenced(Long id) {
        Attachment a = attachmentRepo.findByIdForUpdate(id)
                .orElseThrow(() -> new NotFoundException("附件不存在"));
        long refs = linkRepo.countByAttachmentId(id);
        if (refs > 0) {
            throw new ReferencedException(refs);
        }
        String storageKey = a.getStorageKey();
        attachmentRepo.delete(a);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                storage.deleteFinalQuietly(storageKey);
                storage.deleteTempDirQuietly(id);
            }
        });
    }

    // ------------------------------------------------------------------
    // 过期与失败清理
    // ------------------------------------------------------------------

    /**
     * 清理过期会话与失败上传的临时分片。
     * @return 过期会话数与删除的临时目录数
     */
    @Transactional
    public CleanupResult cleanupExpiredAndFailed() {
        List<Attachment> stale = attachmentRepo.findByStatusAndExpiresAtBefore(
                Attachment.STATUS_UPLOADING, LocalDateTime.now());
        for (Attachment a : stale) {
            a.setStatus(Attachment.STATUS_EXPIRED);
            attachmentRepo.save(a);
        }
        int removedDirs = storage.cleanTempDirs(uploadId ->
                attachmentRepo.findById(uploadId)
                        .map(a -> !Attachment.STATUS_UPLOADING.equals(a.getStatus()))
                        .orElse(true)); // 无对应记录的孤儿目录一并清理
        return new CleanupResult(stale.size(), removedDirs);
    }

    public record CleanupResult(int expiredSessions, int removedTempDirs) {}

    private boolean isExpired(Attachment a) {
        return a.getExpiresAt() != null && LocalDateTime.now().isAfter(a.getExpiresAt());
    }

    private void markExpired(Attachment a) {
        a.setStatus(Attachment.STATUS_EXPIRED);
        attachmentRepo.save(a);
    }
}
