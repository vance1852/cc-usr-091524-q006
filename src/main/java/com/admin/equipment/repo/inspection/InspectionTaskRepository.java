package com.admin.equipment.repo.inspection;

import com.admin.equipment.model.inspection.InspectionTask;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface InspectionTaskRepository extends JpaRepository<InspectionTask, Long> {
    List<InspectionTask> findAllByOrderByCreatedAtDesc();
    List<InspectionTask> findByPlanIdOrderByCreatedAtDesc(Long planId);
    List<InspectionTask> findByStatusOrderByCreatedAtDesc(String status);
    List<InspectionTask> findByAssigneeIdOrderByCreatedAtDesc(Long assigneeId);
    List<InspectionTask> findByScheduledStartBetweenOrderByCreatedAtDesc(LocalDateTime start, LocalDateTime end);
    boolean existsByPlanIdAndScheduledStartBetween(Long planId, LocalDateTime start, LocalDateTime end);
    long countByStatus(String status);
}
