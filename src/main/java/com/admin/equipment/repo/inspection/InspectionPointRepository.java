package com.admin.equipment.repo.inspection;

import com.admin.equipment.model.inspection.InspectionPoint;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface InspectionPointRepository extends JpaRepository<InspectionPoint, Long> {
    boolean existsByCode(String code);
    Optional<InspectionPoint> findByCode(String code);
    List<InspectionPoint> findAllByOrderByCodeAsc();
    List<InspectionPoint> findByEquipmentTypeOrderByCodeAsc(String equipmentType);
    List<InspectionPoint> findByIdIn(List<Long> ids);
}
