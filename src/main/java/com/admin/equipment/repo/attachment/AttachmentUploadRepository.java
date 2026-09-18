package com.admin.equipment.repo.attachment;

import com.admin.equipment.model.attachment.AttachmentUpload;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AttachmentUploadRepository extends JpaRepository<AttachmentUpload, Long> {

    Optional<AttachmentUpload> findByUploadKey(String uploadKey);

    List<AttachmentUpload> findByStatusOrderByCreatedAtDesc(String status);

    List<AttachmentUpload> findByStatusAndExpiresAtBefore(String status, LocalDateTime cutoff);

    /** 完成接口的幂等控制：并发完成请求在此锁上排队，只有第一个能真正拼装。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from AttachmentUpload u where u.uploadKey = :uploadKey")
    Optional<AttachmentUpload> findByUploadKeyForUpdate(@Param("uploadKey") String uploadKey);
}
