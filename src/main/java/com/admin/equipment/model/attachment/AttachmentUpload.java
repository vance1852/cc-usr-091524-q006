package com.admin.equipment.model.attachment;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 分片上传会话：完成校验前文件只存在于临时目录，且不产生附件-异常绑定。
 * status: uploading / completed / aborted / expired
 */
@Entity
@Table(name = "attachment_uploads", indexes = {
        @Index(name = "idx_upload_status", columnList = "status"),
        @Index(name = "idx_upload_expires", columnList = "expires_at")
})
public class AttachmentUpload {

    public static final String STATUS_UPLOADING = "uploading";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_ABORTED = "aborted";
    public static final String STATUS_EXPIRED = "expired";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "upload_key", length = 64, nullable = false, unique = true)
    private String uploadKey;

    @Column(name = "original_filename", length = 255, nullable = false)
    private String originalFilename;

    @Column(name = "media_type", length = 128, nullable = false)
    private String mediaType;

    @Column(length = 16, nullable = false)
    private String category;

    @Column(name = "total_size", nullable = false)
    private Long totalSize;

    @Column(name = "chunk_size", nullable = false)
    private Long chunkSize;

    @Column(name = "total_chunks", nullable = false)
    private Integer totalChunks;

    /** 客户端声明的 SHA-256，完成时与实际拼装结果比对。 */
    @Column(name = "declared_checksum", length = 64, nullable = false)
    private String declaredChecksum;

    // 完成时校验通过后再回填业务引用
    @Column(name = "abnormality_id")
    private Long abnormalityId;

    @Column(name = "task_id")
    private Long taskId;

    @Column(name = "task_point_id")
    private Long taskPointId;

    @Column(name = "record_id")
    private Long recordId;

    @Column(name = "equipment_id")
    private Long equipmentId;

    @Column(name = "uploaded_by_id")
    private Long uploadedById;

    @Column(name = "uploaded_by_name", length = 64)
    private String uploadedByName = "";

    /** 临时分片所在目录（相对存储根），完成或清理后删除。 */
    @Column(name = "temp_path", length = 512, nullable = false)
    private String tempPath;

    @Column(length = 16, nullable = false)
    private String status = STATUS_UPLOADING;

    @Column(name = "attachment_id")
    private Long attachmentId;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getUploadKey() { return uploadKey; }
    public void setUploadKey(String uploadKey) { this.uploadKey = uploadKey; }
    public String getOriginalFilename() { return originalFilename; }
    public void setOriginalFilename(String originalFilename) { this.originalFilename = originalFilename; }
    public String getMediaType() { return mediaType; }
    public void setMediaType(String mediaType) { this.mediaType = mediaType; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public Long getTotalSize() { return totalSize; }
    public void setTotalSize(Long totalSize) { this.totalSize = totalSize; }
    public Long getChunkSize() { return chunkSize; }
    public void setChunkSize(Long chunkSize) { this.chunkSize = chunkSize; }
    public Integer getTotalChunks() { return totalChunks; }
    public void setTotalChunks(Integer totalChunks) { this.totalChunks = totalChunks; }
    public String getDeclaredChecksum() { return declaredChecksum; }
    public void setDeclaredChecksum(String declaredChecksum) { this.declaredChecksum = declaredChecksum; }
    public Long getAbnormalityId() { return abnormalityId; }
    public void setAbnormalityId(Long abnormalityId) { this.abnormalityId = abnormalityId; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public Long getTaskPointId() { return taskPointId; }
    public void setTaskPointId(Long taskPointId) { this.taskPointId = taskPointId; }
    public Long getRecordId() { return recordId; }
    public void setRecordId(Long recordId) { this.recordId = recordId; }
    public Long getEquipmentId() { return equipmentId; }
    public void setEquipmentId(Long equipmentId) { this.equipmentId = equipmentId; }
    public Long getUploadedById() { return uploadedById; }
    public void setUploadedById(Long uploadedById) { this.uploadedById = uploadedById; }
    public String getUploadedByName() { return uploadedByName; }
    public void setUploadedByName(String uploadedByName) { this.uploadedByName = uploadedByName; }
    public String getTempPath() { return tempPath; }
    public void setTempPath(String tempPath) { this.tempPath = tempPath; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getAttachmentId() { return attachmentId; }
    public void setAttachmentId(Long attachmentId) { this.attachmentId = attachmentId; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }
}
