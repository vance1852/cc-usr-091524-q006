package com.admin.equipment.model.attachment;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 附件与业务对象（巡检异常、维修工单等）的引用关系。
 * 引用只记录指针，不复制文件；同一 (attachment, bizType, bizId) 唯一，
 * 保证并发绑定幂等。存在引用的附件禁止删除。
 */
@Entity
@Table(name = "attachment_links",
        uniqueConstraints = @UniqueConstraint(name = "uk_attachment_biz",
                columnNames = {"attachment_id", "biz_type", "biz_id"}))
public class AttachmentLink {

    public static final String BIZ_ABNORMALITY = "inspection_abnormality";
    public static final String BIZ_WORK_ORDER = "work_order";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "attachment_id", nullable = false)
    private Long attachmentId;

    /** 业务类型：inspection_abnormality / work_order */
    @Column(name = "biz_type", nullable = false, length = 32)
    private String bizType;

    @Column(name = "biz_id", nullable = false)
    private Long bizId;

    @Column(name = "created_by", length = 64)
    private String createdBy = "";

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    public AttachmentLink() {}

    public AttachmentLink(Long attachmentId, String bizType, Long bizId, String createdBy) {
        this.attachmentId = attachmentId;
        this.bizType = bizType;
        this.bizId = bizId;
        this.createdBy = createdBy == null ? "" : createdBy;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getAttachmentId() { return attachmentId; }
    public void setAttachmentId(Long attachmentId) { this.attachmentId = attachmentId; }
    public String getBizType() { return bizType; }
    public void setBizType(String bizType) { this.bizType = bizType; }
    public Long getBizId() { return bizId; }
    public void setBizId(Long bizId) { this.bizId = bizId; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
