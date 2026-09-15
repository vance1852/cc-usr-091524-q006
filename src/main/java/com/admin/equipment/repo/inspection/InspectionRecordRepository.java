package com.admin.equipment.repo.inspection;

import com.admin.equipment.model.inspection.InspectionRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InspectionRecordRepository extends JpaRepository<InspectionRecord, Long> {
    List<InspectionRecord> findByTaskPointIdOrderByIdAsc(Long taskPointId);
    List<InspectionRecord> findByTaskIdOrderByIdAsc(Long taskId);
    List<InspectionRecord> findByPointIdOrderByRecordedAtDesc(Long pointId);
    List<InspectionRecord> findByTemplateItemIdOrderByRecordedAtDesc(Long templateItemId);
    long countByTaskPointIdAndIsQualifiedFalse(Long taskPointId);
    long countByTaskPointIdAndIsAbnormalTrue(Long taskPointId);
}
