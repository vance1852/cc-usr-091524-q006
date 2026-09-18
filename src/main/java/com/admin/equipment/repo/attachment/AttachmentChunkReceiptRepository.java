package com.admin.equipment.repo.attachment;

import com.admin.equipment.model.attachment.AttachmentChunkReceipt;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AttachmentChunkReceiptRepository extends JpaRepository<AttachmentChunkReceipt, Long> {

    List<AttachmentChunkReceipt> findByUploadIdOrderByChunkIndexAsc(Long uploadId);

    Optional<AttachmentChunkReceipt> findByUploadIdAndChunkIndex(Long uploadId, Integer chunkIndex);

    long countByUploadId(Long uploadId);

    @Modifying
    @Query("delete from AttachmentChunkReceipt c where c.uploadId = :uploadId")
    void deleteByUploadId(@Param("uploadId") Long uploadId);

    /** 完成拼装时对收据加锁，与并发分片上传互斥。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from AttachmentChunkReceipt c where c.uploadId = :uploadId order by c.chunkIndex asc")
    List<AttachmentChunkReceipt> findByUploadIdForUpdate(@Param("uploadId") Long uploadId);
}
