package com.admin.equipment.model.attachment;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 受控附件元数据。文件内容存储在本地目录，数据库只保存元数据、
 * SHA-256、大小、媒体类型与上传者；业务引用通过 attachment_links 关联。
 */
@Entity
@Table(name = "attachments")
public class Attachment {

    /** 上传中：分片尚未完成确认 */
    public static final String STATUS_UPLOADING = "uploading";
    /** 已完成：校验和与声明大小均已核验，可绑定业务 */
    public static final String STATUS_COMPLETED = "completed";
    /** 失败：完成确认时校验失败，临时分片待清理 */
    public static final String STATUS_FAILED = "failed";
    /** 过期：会话超时未完成，临时分片待清理 */
    public static final String STATUS_EXPIRED = "expired";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 服务端生成的存储键（32 位十六进制），客户端不可控，防止路径穿越 */
    @Column(name = "storage_key", nullable = false, unique = true, length = 64)
    private String storageKey;

    @Column(name = "original_name", nullable = false, length = 256)
    private String originalName;

    @Column(name = "media_type", nullable = false, length = 128)
    private String mediaType;

    /** 类别：image / audio / document */
    @Column(nullable = false, length = 16)
    private String category = "document";

    /** 上传发起时声明、完成确认时核验的文件大小 */
    @Column(name = "size_bytes", nullable = false)
    private Long sizeBytes;

    /** 上传发起时声明、完成确认时核验的 SHA-256（hex） */
    @Column(nullable = false, length = 64)
    private String sha256;

    @Column(nullable = false, length = 16)
    private String status = STATUS_UPLOADING;

    @Column(name = "uploaded_by")
    private Long uploadedBy;

    @Column(name = "uploaded_by_name", length = 64)
    private String uploadedByName = "";

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    /** 上传会话过期时间，过期后临时分片可清理 */
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "fail_reason", length = 256)
    private String failReason = "";

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getStorageKey() { return storageKey; }
    public void setStorageKey(String storageKey) { this.storageKey = storageKey; }
    public String getOriginalName() { return originalName; }
    public void setOriginalName(String originalName) { this.originalName = originalName; }
    public String getMediaType() { return mediaType; }
    public void setMediaType(String mediaType) { this.mediaType = mediaType; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public Long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(Long sizeBytes) { this.sizeBytes = sizeBytes; }
    public String getSha256() { return sha256; }
    public void setSha256(String sha256) { this.sha256 = sha256; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getUploadedBy() { return uploadedBy; }
    public void setUploadedBy(Long uploadedBy) { this.uploadedBy = uploadedBy; }
    public String getUploadedByName() { return uploadedByName; }
    public void setUploadedByName(String uploadedByName) { this.uploadedByName = uploadedByName; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
    public String getFailReason() { return failReason; }
    public void setFailReason(String failReason) { this.failReason = failReason; }
}
