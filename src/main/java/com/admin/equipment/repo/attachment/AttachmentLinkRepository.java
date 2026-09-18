package com.admin.equipment.repo.attachment;

import com.admin.equipment.model.attachment.AttachmentLink;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AttachmentLinkRepository extends JpaRepository<AttachmentLink, Long> {

    List<AttachmentLink> findByBizTypeAndBizId(String bizType, Long bizId);

    List<AttachmentLink> findByAttachmentId(Long attachmentId);

    long countByAttachmentId(Long attachmentId);

    boolean existsByAttachmentIdAndBizTypeAndBizId(Long attachmentId, String bizType, Long bizId);

    Optional<AttachmentLink> findByAttachmentIdAndBizTypeAndBizId(Long attachmentId, String bizType, Long bizId);

    void deleteByAttachmentIdAndBizTypeAndBizId(Long attachmentId, String bizType, Long bizId);
}
