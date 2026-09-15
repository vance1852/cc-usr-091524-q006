package com.admin.equipment.service.inspection;

import com.admin.equipment.model.inspection.InspectionPoint;
import com.admin.equipment.repo.inspection.InspectionPointRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class InspectionPointService {

    private final InspectionPointRepository repo;

    public InspectionPointService(InspectionPointRepository repo) {
        this.repo = repo;
    }

    public List<InspectionPoint> listAll() {
        return repo.findAllByOrderByCodeAsc();
    }

    public List<InspectionPoint> listByEquipmentType(String type) {
        return repo.findByEquipmentTypeOrderByCodeAsc(type);
    }

    public Optional<InspectionPoint> getById(Long id) {
        return repo.findById(id);
    }

    public List<InspectionPoint> getByIds(List<Long> ids) {
        return repo.findByIdIn(ids);
    }

    @Transactional
    public InspectionPoint create(String code, String name, String location, Double coordX,
                                   Double coordY, String equipmentIds, String equipmentType) {
        if (code == null || code.isBlank()) throw new IllegalArgumentException("编号必填");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("名称必填");
        if (repo.existsByCode(code)) throw new IllegalArgumentException("编号已存在");
        InspectionPoint p = new InspectionPoint();
        p.setCode(code);
        p.setName(name);
        p.setLocation(location == null ? "" : location);
        p.setCoordX(coordX);
        p.setCoordY(coordY);
        p.setEquipmentIds(equipmentIds == null ? "" : equipmentIds);
        p.setEquipmentType(equipmentType == null ? "" : equipmentType);
        return repo.save(p);
    }

    @Transactional
    public InspectionPoint update(Long id, String name, String location, Double coordX,
                                   Double coordY, String equipmentIds, String equipmentType) {
        InspectionPoint p = repo.findById(id).orElseThrow(() -> new IllegalArgumentException("巡检点不存在"));
        if (name != null && !name.isBlank()) p.setName(name);
        if (location != null) p.setLocation(location);
        if (coordX != null) p.setCoordX(coordX);
        if (coordY != null) p.setCoordY(coordY);
        if (equipmentIds != null) p.setEquipmentIds(equipmentIds);
        if (equipmentType != null) p.setEquipmentType(equipmentType);
        return repo.save(p);
    }

    @Transactional
    public void delete(Long id) {
        if (!repo.existsById(id)) throw new IllegalArgumentException("巡检点不存在");
        repo.deleteById(id);
    }

    public List<Long> parseEquipmentIds(String equipmentIdsStr) {
        List<Long> result = new ArrayList<>();
        if (equipmentIdsStr == null || equipmentIdsStr.isBlank()) return result;
        String[] parts = equipmentIdsStr.split(",");
        for (String part : parts) {
            try {
                Long id = Long.parseLong(part.trim());
                if (id > 0) result.add(id);
            } catch (NumberFormatException ignored) {}
        }
        return result;
    }

    public String joinEquipmentIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return "";
        List<String> strs = new ArrayList<>();
        for (Long id : ids) strs.add(String.valueOf(id));
        return String.join(",", strs);
    }
}
