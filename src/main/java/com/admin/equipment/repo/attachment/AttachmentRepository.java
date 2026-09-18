package com.admin.equipment.repo.attachment;

import com.admin.equipment.model.attachment.Attachment;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AttachmentRepository extends JpaRepository<Attachment, Long> {

    List<Attachment> findByStatusAndExpiresAtBefore(String status, LocalDateTime before);

    /** 悲观写锁：完成确认与删除等关键操作串行化，避免并发重复落盘。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Attachment a where a.id = :id")
    Optional<Attachment> findByIdForUpdate(@Param("id") Long id);
}
