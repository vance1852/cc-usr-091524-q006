package com.admin.equipment.repo.inspection;

import com.admin.equipment.model.inspection.InspectionTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface InspectionTemplateRepository extends JpaRepository<InspectionTemplate, Long> {
    boolean existsByCode(String code);
    Optional<InspectionTemplate> findByCode(String code);
    List<InspectionTemplate> findAllByOrderByCodeAsc();
    List<InspectionTemplate> findByEquipmentTypeOrderByCodeAsc(String equipmentType);
}
