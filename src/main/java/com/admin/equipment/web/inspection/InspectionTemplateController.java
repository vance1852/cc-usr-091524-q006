package com.admin.equipment.web.inspection;

import com.admin.equipment.model.inspection.InspectionTemplate;
import com.admin.equipment.model.inspection.InspectionTemplateItem;
import com.admin.equipment.service.inspection.InspectionTemplateService;
import com.admin.equipment.service.inspection.InspectionTemplateService.ItemSpec;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/inspection/templates")
public class InspectionTemplateController {

    private final InspectionTemplateService service;

    public InspectionTemplateController(InspectionTemplateService service) {
        this.service = service;
    }

    public record TemplateRequest(String code, String name, String equipmentType,
                                   String description, List<ItemSpec> items) {}

    @GetMapping
    public List<InspectionTemplate> list(@RequestParam(required = false) String equipmentType) {
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
                        .body(Map.of("detail", "模板不存在")));
    }

    @GetMapping("/{id}/items")
    public ResponseEntity<?> listItems(@PathVariable Long id) {
        if (!service.getById(id).isPresent()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "模板不存在"));
        }
        List<InspectionTemplateItem> items = service.getItems(id);
        return ResponseEntity.ok(items);
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody TemplateRequest req) {
        try {
            InspectionTemplate t = service.create(req.code(), req.name(), req.equipmentType(),
                    req.description(), req.items());
            return ResponseEntity.status(HttpStatus.CREATED).body(t);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody TemplateRequest req) {
        try {
            InspectionTemplate t = service.update(id, req.name(), req.equipmentType(),
                    req.description(), req.items());
            return ResponseEntity.ok(t);
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
