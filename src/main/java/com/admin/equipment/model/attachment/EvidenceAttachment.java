package com.admin.equipment.model.attachment;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 异常证据附件：只保存元数据、校验和与业务引用，文件内容落在配置的本地目录。
 * 异常转工单时通过 workOrderId 保留引用，不复制文件。
 */
@Entity
@Table(name = "inspection_attachments", indexes = {
        @Index(name = "idx_attachment_abnormality", columnList = "abnormality_id"),
        @Index(name = "idx_attachment_work_order", columnList = "work_order_id")
})
public class EvidenceAttachment {

    public static final String STATUS_OK = "ok";
    public static final String STATUS_MISSING = "missing";
    public static final String STATUS_SIZE_MISMATCH = "size_mismatch";
    public static final String STATUS_CORRUPT = "corrupt";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 绑定的巡检异常，未通过校验的上传不会产生此引用。 */
    @Column(name = "abnormality_id")
    private Long abnormalityId;

    // 业务引用（由异常快照，便于闭环记录直接展示来源）
    @Column(name = "task_id")
    private Long taskId;

    @Column(name = "task_point_id")
    private Long taskPointId;

    @Column(name = "record_id")
    private Long recordId;

    @Column(name = "equipment_id")
    private Long equipmentId;

    @Column(name = "work_order_id")
    private Long workOrderId;

    @Column(name = "original_filename", length = 255, nullable = false)
    private String originalFilename;

    /** 相对于存储根目录的服务端生成路径，永不使用客户端输入拼接。 */
    @Column(name = "storage_path", length = 512, nullable = false)
    private String storagePath;

    @Column(name = "media_type", length = 128, nullable = false)
    private String mediaType;

    /** image / audio / document */
    @Column(length = 16, nullable = false)
    private String category;

    @Column(name = "file_size", nullable = false)
    private Long fileSize;

    @Column(name = "checksum_sha256", length = 64, nullable = false)
    private String checksumSha256;

    @Column(name = "uploaded_by_id")
    private Long uploadedById;

    @Column(name = "uploaded_by_name", length = 64)
    private String uploadedByName = "";

    @Column(name = "integrity_status", length = 16, nullable = false)
    private String integrityStatus = STATUS_OK;

    @Column(name = "verified_at")
    private LocalDateTime verifiedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
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
    public Long getWorkOrderId() { return workOrderId; }
    public void setWorkOrderId(Long workOrderId) { this.workOrderId = workOrderId; }
    public String getOriginalFilename() { return originalFilename; }
    public void setOriginalFilename(String originalFilename) { this.originalFilename = originalFilename; }
    public String getStoragePath() { return storagePath; }
    public void setStoragePath(String storagePath) { this.storagePath = storagePath; }
    public String getMediaType() { return mediaType; }
    public void setMediaType(String mediaType) { this.mediaType = mediaType; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long fileSize) { this.fileSize = fileSize; }
    public String getChecksumSha256() { return checksumSha256; }
    public void setChecksumSha256(String checksumSha256) { this.checksumSha256 = checksumSha256; }
    public Long getUploadedById() { return uploadedById; }
    public void setUploadedById(Long uploadedById) { this.uploadedById = uploadedById; }
    public String getUploadedByName() { return uploadedByName; }
    public void setUploadedByName(String uploadedByName) { this.uploadedByName = uploadedByName; }
    public String getIntegrityStatus() { return integrityStatus; }
    public void setIntegrityStatus(String integrityStatus) { this.integrityStatus = integrityStatus; }
    public LocalDateTime getVerifiedAt() { return verifiedAt; }
    public void setVerifiedAt(LocalDateTime verifiedAt) { this.verifiedAt = verifiedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
