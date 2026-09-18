package com.admin.equipment.service.attachment.dto;

import java.time.LocalDateTime;

/** 质量工程师查看闭环记录时看到的证据视图：完整性状态 + 来源。 */
public record AttachmentView(Long id,
                             Long abnormalityId,
                             Long taskId,
                             Long taskPointId,
                             Long recordId,
                             Long equipmentId,
                             Long workOrderId,
                             String originalFilename,
                             String mediaType,
                             String category,
                             long fileSize,
                             String checksumSha256,
                             Long uploadedById,
                             String uploadedByName,
                             String integrityStatus,
                             String integrityDetail,
                             boolean contentAvailable,
                             LocalDateTime verifiedAt,
                             LocalDateTime createdAt) {
}
