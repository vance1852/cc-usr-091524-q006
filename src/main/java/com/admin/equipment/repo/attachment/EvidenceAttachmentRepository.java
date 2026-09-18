package com.admin.equipment.repo.attachment;

import com.admin.equipment.model.attachment.EvidenceAttachment;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface EvidenceAttachmentRepository extends JpaRepository<EvidenceAttachment, Long> {

    List<EvidenceAttachment> findByAbnormalityIdOrderByCreatedAtDesc(Long abnormalityId);

    List<EvidenceAttachment> findByWorkOrderIdOrderByCreatedAtDesc(Long workOrderId);

    long countByAbnormalityId(Long abnormalityId);

    /** 悲观锁：并发绑定/删除时串行化，保证引用计数与文件处置的一致性。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from EvidenceAttachment a where a.id = :id")
    Optional<EvidenceAttachment> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from EvidenceAttachment a where a.abnormalityId = :abnormalityId order by a.id asc")
    List<EvidenceAttachment> findByAbnormalityIdForUpdate(@Param("abnormalityId") Long abnormalityId);
}
