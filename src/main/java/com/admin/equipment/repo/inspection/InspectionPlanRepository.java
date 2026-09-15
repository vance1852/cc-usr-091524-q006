package com.admin.equipment.repo.inspection;

import com.admin.equipment.model.inspection.InspectionPlan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface InspectionPlanRepository extends JpaRepository<InspectionPlan, Long> {
    boolean existsByCode(String code);
    Optional<InspectionPlan> findByCode(String code);
    List<InspectionPlan> findAllByOrderByCodeAsc();
    List<InspectionPlan> findByEnabledTrueOrderByCodeAsc();
    List<InspectionPlan> findByTemplateId(Long templateId);
}
