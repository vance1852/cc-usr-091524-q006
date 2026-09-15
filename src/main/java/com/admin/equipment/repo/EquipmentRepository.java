package com.admin.equipment.repo;

import com.admin.equipment.model.Equipment;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EquipmentRepository extends JpaRepository<Equipment, Long> {
    boolean existsByCode(String code);
    long countByStatus(String status);
}
