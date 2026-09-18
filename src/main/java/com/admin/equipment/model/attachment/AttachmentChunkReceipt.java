package com.admin.equipment.model.attachment;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 分片收据：每个序号一行，唯一约束保证同一分片重复上传不会产生重复记录，
 * 断点续传时据此返回缺失分片。
 */
@Entity
@Table(name = "attachment_chunks", uniqueConstraints =
        @UniqueConstraint(name = "uk_chunk_upload_index", columnNames = {"upload_id", "chunk_index"}))
public class AttachmentChunkReceipt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "upload_id", nullable = false)
    private Long uploadId;

    @Column(name = "chunk_index", nullable = false)
    private Integer chunkIndex;

    @Column(name = "chunk_size", nullable = false)
    private Long chunkSize;

    @Column(name = "received_at", nullable = false)
    private LocalDateTime receivedAt = LocalDateTime.now();

    public AttachmentChunkReceipt() {}

    public AttachmentChunkReceipt(Long uploadId, Integer chunkIndex, Long chunkSize) {
        this.uploadId = uploadId;
        this.chunkIndex = chunkIndex;
        this.chunkSize = chunkSize;
        this.receivedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUploadId() { return uploadId; }
    public void setUploadId(Long uploadId) { this.uploadId = uploadId; }
    public Integer getChunkIndex() { return chunkIndex; }
    public void setChunkIndex(Integer chunkIndex) { this.chunkIndex = chunkIndex; }
    public Long getChunkSize() { return chunkSize; }
    public void setChunkSize(Long chunkSize) { this.chunkSize = chunkSize; }
    public LocalDateTime getReceivedAt() { return receivedAt; }
    public void setReceivedAt(LocalDateTime receivedAt) { this.receivedAt = receivedAt; }
}
