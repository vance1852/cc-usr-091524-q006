package com.admin.equipment.service.attachment.dto;

import java.util.List;

public record ChunkReceiptResult(String uploadKey,
                                 int chunkIndex,
                                 long chunkSize,
                                 boolean replaced,
                                 int receivedCount,
                                 int totalChunks,
                                 List<Integer> receivedChunks,
                                 List<Integer> missingChunks,
                                 boolean completed) {
}
