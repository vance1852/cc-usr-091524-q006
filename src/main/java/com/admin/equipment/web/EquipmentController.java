package com.admin.equipment.web;

import com.admin.equipment.model.Equipment;
import com.admin.equipment.repo.EquipmentRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/equipments")
public class EquipmentController {

    private static final Set<String> STATUSES = Set.of("normal", "warning", "fault", "maintenance");

    private final EquipmentRepository repo;

    public EquipmentController(EquipmentRepository repo) {
        this.repo = repo;
    }

    public record EquipmentRequest(String code, String name, String location, String type, String status) {}

    @GetMapping
    public List<Equipment> list() {
        return repo.findAll();
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody EquipmentRequest req) {
        if (req.code() == null || req.code().isBlank() || req.name() == null || req.name().isBlank()) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", "编号和名称必填"));
        }
        if (repo.existsByCode(req.code())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("detail", "设备编号已存在"));
        }
        Equipment e = new Equipment();
        e.setCode(req.code());
        e.setName(req.name());
        e.setLocation(req.location() == null ? "" : req.location());
        e.setType(req.type() == null ? "" : req.type());
        e.setStatus(validStatus(req.status()) ? req.status() : "normal");
        return ResponseEntity.status(HttpStatus.CREATED).body(repo.save(e));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable Long id) {
        return repo.findById(id)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "设备不存在")));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody EquipmentRequest req) {
        Equipment e = repo.findById(id).orElse(null);
        if (e == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "设备不存在"));
        }
        if (req.name() != null && !req.name().isBlank()) e.setName(req.name());
        if (req.location() != null) e.setLocation(req.location());
        if (req.type() != null) e.setType(req.type());
        if (validStatus(req.status())) e.setStatus(req.status());
        return ResponseEntity.ok(repo.save(e));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        if (!repo.existsById(id)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "设备不存在"));
        }
        repo.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    private boolean validStatus(String s) {
        return s != null && STATUSES.contains(s);
    }
}
