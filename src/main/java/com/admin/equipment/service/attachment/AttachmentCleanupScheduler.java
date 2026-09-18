package com.admin.equipment.service.attachment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 定时清理过期/失败上传会话的临时分片。 */
@Component
public class AttachmentCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(AttachmentCleanupScheduler.class);

    private final AttachmentService attachmentService;

    public AttachmentCleanupScheduler(AttachmentService attachmentService) {
        this.attachmentService = attachmentService;
    }

    @Scheduled(initialDelayString = "${app.attachment.cleanup-interval-ms:3600000}",
            fixedDelayString = "${app.attachment.cleanup-interval-ms:3600000}")
    public void cleanup() {
        AttachmentService.CleanupResult r = attachmentService.cleanupExpiredAndFailed();
        if (r.expiredSessions() > 0 || r.removedTempDirs() > 0) {
            log.info("附件临时分片清理完成: 过期会话 {} 个, 删除临时目录 {} 个",
                    r.expiredSessions(), r.removedTempDirs());
        }
    }
}
