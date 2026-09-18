package com.admin.equipment.service.attachment;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** 受控附件配置：存储目录、大小上限、分片大小、会话有效期与媒体类型白名单。 */
@Component
@ConfigurationProperties(prefix = "app.attachment")
public class AttachmentProperties {

    /** 文件内容落盘的根目录（可配置本地目录） */
    private String dir = "./data/attachments";

    /** 单文件最大字节数 */
    private long maxSizeBytes = 100L * 1024 * 1024;

    /** 分片上传单片字节数上限 */
    private long chunkSizeBytes = 5L * 1024 * 1024;

    /** 上传会话有效期（小时） */
    private int sessionExpireHours = 24;

    /** 定时清理间隔（毫秒） */
    private long cleanupIntervalMs = 3600000L;

    /** 允许的媒体类型白名单（图片/音频/文档） */
    private List<String> allowedMediaTypes = new ArrayList<>();

    public String getDir() { return dir; }
    public void setDir(String dir) { this.dir = dir; }
    public long getMaxSizeBytes() { return maxSizeBytes; }
    public void setMaxSizeBytes(long maxSizeBytes) { this.maxSizeBytes = maxSizeBytes; }
    public long getChunkSizeBytes() { return chunkSizeBytes; }
    public void setChunkSizeBytes(long chunkSizeBytes) { this.chunkSizeBytes = chunkSizeBytes; }
    public int getSessionExpireHours() { return sessionExpireHours; }
    public void setSessionExpireHours(int sessionExpireHours) { this.sessionExpireHours = sessionExpireHours; }
    public long getCleanupIntervalMs() { return cleanupIntervalMs; }
    public void setCleanupIntervalMs(long cleanupIntervalMs) { this.cleanupIntervalMs = cleanupIntervalMs; }
    public List<String> getAllowedMediaTypes() { return allowedMediaTypes; }
    public void setAllowedMediaTypes(List<String> allowedMediaTypes) { this.allowedMediaTypes = allowedMediaTypes; }

    public boolean isMediaTypeAllowed(String mediaType) {
        if (mediaType == null) return false;
        for (String t : allowedMediaTypes) {
            if (t != null && t.trim().equalsIgnoreCase(mediaType.trim())) return true;
        }
        return false;
    }

    /** 依据媒体类型推导附件类别：image / audio / document。 */
    public static String categoryOf(String mediaType) {
        if (mediaType == null) return "document";
        String mt = mediaType.toLowerCase();
        if (mt.startsWith("image/")) return "image";
        if (mt.startsWith("audio/")) return "audio";
        return "document";
    }
}
