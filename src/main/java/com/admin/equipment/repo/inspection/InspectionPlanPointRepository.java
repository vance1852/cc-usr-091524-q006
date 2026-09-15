package com.admin.equipment.repo.inspection;

import com.admin.equipment.model.inspection.InspectionPlanPoint;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InspectionPlanPointRepository extends JpaRepository<InspectionPlanPoint, Long> {
    List<InspectionPlanPoint> findByPlanIdOrderBySequenceNoAsc(Long planId);
    void deleteByPlanId(Long planId);
    long countByPlanId(Long planId);
    List<InspectionPlanPoint> findByPointId(Long pointId);
}
