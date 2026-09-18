package com.admin.equipment.service.attachment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时清理过期/失败的分片上传临时目录，避免磁盘泄漏。
 * 频率可配置，默认每小时一次。
 */
@Component
public class AttachmentCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(AttachmentCleanupScheduler.class);

    private final AttachmentService attachmentService;

    public AttachmentCleanupScheduler(AttachmentService attachmentService) {
        this.attachmentService = attachmentService;
    }

    @Scheduled(cron = "${app.attachment.cleanup-cron:0 17 * * * *}")
    public void cleanup() {
        try {
            AttachmentService.CleanupResult r = attachmentService.cleanupExpiredSessions();
            if (r.expiredSessions() > 0 || r.orphanTempDirs() > 0) {
                log.info("附件临时分片清理：过期会话 {}，孤儿目录 {}",
                        r.expiredSessions(), r.orphanTempDirs());
            }
        } catch (Exception e) {
            log.warn("附件定时清理失败: {}", e.getMessage());
        }
    }
}
