package com.admin.equipment.repo.inspection;

import com.admin.equipment.model.inspection.InspectionAbnormality;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface InspectionAbnormalityRepository extends JpaRepository<InspectionAbnormality, Long> {
    List<InspectionAbnormality> findAllByOrderByReportedAtDesc();
    List<InspectionAbnormality> findByTaskIdOrderByReportedAtDesc(Long taskId);
    List<InspectionAbnormality> findByTaskPointIdOrderByReportedAtDesc(Long taskPointId);
    List<InspectionAbnormality> findByEquipmentIdOrderByReportedAtDesc(Long equipmentId);
    List<InspectionAbnormality> findByStatusOrderByReportedAtDesc(String status);
    List<InspectionAbnormality> findByWorkOrderId(Long workOrderId);
    Optional<InspectionAbnormality> findByTaskPointIdAndRecordIdAndEquipmentId(Long taskPointId, Long recordId, Long equipmentId);
    boolean existsByWorkOrderId(Long workOrderId);
}
