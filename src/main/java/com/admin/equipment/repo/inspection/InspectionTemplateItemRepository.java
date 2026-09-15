package com.admin.equipment.repo.inspection;

import com.admin.equipment.model.inspection.InspectionTemplateItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InspectionTemplateItemRepository extends JpaRepository<InspectionTemplateItem, Long> {
    List<InspectionTemplateItem> findByTemplateIdOrderBySortOrderAsc(Long templateId);
    void deleteByTemplateId(Long templateId);
    long countByTemplateId(Long templateId);
}
