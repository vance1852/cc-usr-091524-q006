package com.admin.equipment.service.attachment.dto;

import java.time.LocalDateTime;
import java.util.List;

public record UploadInitiateResult(String uploadKey,
                                   String originalFilename,
                                   String mediaType,
                                   String category,
                                   long totalSize,
                                   long chunkSize,
                                   int totalChunks,
                                   String checksumSha256,
                                   List<Integer> receivedChunks,
                                   LocalDateTime expiresAt) {
}
