package com.admin.equipment.service.attachment;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 受控附件配置（app.attachment.*）：
 * 存储目录可通过环境变量覆盖，容器中可挂载持久卷。
 */
@Component
@ConfigurationProperties(prefix = "app.attachment")
public class AttachmentProperties {

    /** 文件内容根目录（MySQL 只保存相对路径与元数据）。 */
    private String storageDir = "./data/attachments";

    /** 允许绑定异常的媒体类型白名单。 */
    private List<String> allowedTypes = List.of(
            "image/jpeg", "image/png", "image/webp",
            "audio/mpeg", "audio/mp4", "audio/aac",
            "audio/wav", "audio/x-wav", "audio/ogg", "audio/webm",
            "application/pdf", "text/plain",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    );

    /** 单个附件大小上限，默认 100MB。 */
    private long maxSizeBytes = 100L * 1024 * 1024;

    /** 单个分片大小上限，默认 10MB。 */
    private long maxChunkSizeBytes = 10L * 1024 * 1024;

    /** 分片会话有效期（小时），过期会话的临时分片可被清理。 */
    private int sessionTtlHours = 24;

    public String getStorageDir() { return storageDir; }
    public void setStorageDir(String storageDir) { this.storageDir = storageDir; }
    public List<String> getAllowedTypes() { return allowedTypes; }
    public void setAllowedTypes(List<String> allowedTypes) { this.allowedTypes = allowedTypes; }
    public long getMaxSizeBytes() { return maxSizeBytes; }
    public void setMaxSizeBytes(long maxSizeBytes) { this.maxSizeBytes = maxSizeBytes; }
    public long getMaxChunkSizeBytes() { return maxChunkSizeBytes; }
    public void setMaxChunkSizeBytes(long maxChunkSizeBytes) { this.maxChunkSizeBytes = maxChunkSizeBytes; }
    public int getSessionTtlHours() { return sessionTtlHours; }
    public void setSessionTtlHours(int sessionTtlHours) { this.sessionTtlHours = sessionTtlHours; }
}
