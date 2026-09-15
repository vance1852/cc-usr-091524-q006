package com.admin.equipment.repo.inspection;

import com.admin.equipment.model.inspection.InspectionTaskPoint;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface InspectionTaskPointRepository extends JpaRepository<InspectionTaskPoint, Long> {
    List<InspectionTaskPoint> findByTaskIdOrderByPlannedSequenceAsc(Long taskId);
    List<InspectionTaskPoint> findByTaskIdOrderByActualSequenceAsc(Long taskId);
    List<InspectionTaskPoint> findByPointIdOrderByIdDesc(Long pointId);
    Optional<InspectionTaskPoint> findByTaskIdAndPointId(Long taskId, Long pointId);
    long countByTaskIdAndStatus(Long taskId, String status);
    long countByTaskIdAndIsMissedTrue(Long taskId);
}
