package com.admin.equipment.web.inspection;

import com.admin.equipment.model.inspection.InspectionPoint;
import com.admin.equipment.service.inspection.InspectionPointService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/inspection/points")
public class InspectionPointController {

    private final InspectionPointService service;

    public InspectionPointController(InspectionPointService service) {
        this.service = service;
    }

    public record PointRequest(String code, String name, String location, Double coordX,
                               Double coordY, String equipmentIds, String equipmentType) {}

    @GetMapping
    public List<InspectionPoint> list(@RequestParam(required = false) String equipmentType) {
        if (equipmentType != null && !equipmentType.isBlank()) {
            return service.listByEquipmentType(equipmentType);
        }
        return service.listAll();
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable Long id) {
        return service.getById(id)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("detail", "巡检点不存在")));
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody PointRequest req) {
        try {
            InspectionPoint p = service.create(req.code(), req.name(), req.location(),
                    req.coordX(), req.coordY(), req.equipmentIds(), req.equipmentType());
            return ResponseEntity.status(HttpStatus.CREATED).body(p);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody PointRequest req) {
        try {
            InspectionPoint p = service.update(id, req.name(), req.location(),
                    req.coordX(), req.coordY(), req.equipmentIds(), req.equipmentType());
            return ResponseEntity.ok(p);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        try {
            service.delete(id);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", e.getMessage()));
        }
    }
}
