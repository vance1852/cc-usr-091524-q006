package com.admin.equipment.web;

import com.admin.equipment.model.WorkOrder;
import com.admin.equipment.repo.EquipmentRepository;
import com.admin.equipment.repo.WorkOrderRepository;
import com.admin.equipment.service.attachment.AttachmentService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/work-orders")
public class WorkOrderController {

    private static final Set<String> TYPES = Set.of("inspection", "repair", "maintenance");
    private static final Set<String> PRIORITIES = Set.of("low", "medium", "high", "urgent");
    private static final Set<String> STATUSES = Set.of("open", "in_progress", "done");

    private final WorkOrderRepository repo;
    private final EquipmentRepository equipmentRepo;
    private final AttachmentService attachmentService;

    public WorkOrderController(WorkOrderRepository repo, EquipmentRepository equipmentRepo,
                               AttachmentService attachmentService) {
        this.repo = repo;
        this.equipmentRepo = equipmentRepo;
        this.attachmentService = attachmentService;
    }

    public record WorkOrderRequest(Long equipmentId, String title, String type, String priority,
                                   String description, String assignee) {}

    public record StatusRequest(String status) {}

    @GetMapping
    public List<WorkOrder> list(@RequestParam(required = false) Long equipmentId,
                                @RequestParam(required = false) String status) {
        if (equipmentId != null) {
            return repo.findByEquipmentIdOrderByIdDesc(equipmentId);
        }
        if (status != null) {
            return repo.findByStatusOrderByIdDesc(status);
        }
        return repo.findAllByOrderByIdDesc();
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody WorkOrderRequest req) {
        if (req.equipmentId() == null || req.title() == null || req.title().isBlank()) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", "设备和标题必填"));
        }
        if (!equipmentRepo.existsById(req.equipmentId())) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "设备不存在"));
        }
        WorkOrder w = new WorkOrder();
        w.setEquipmentId(req.equipmentId());
        w.setTitle(req.title());
        w.setType(TYPES.contains(req.type()) ? req.type() : "inspection");
        w.setPriority(PRIORITIES.contains(req.priority()) ? req.priority() : "medium");
        w.setDescription(req.description() == null ? "" : req.description());
        w.setAssignee(req.assignee() == null ? "" : req.assignee());
        w.setStatus("open");
        return ResponseEntity.status(HttpStatus.CREATED).body(repo.save(w));
    }

    /** 工单引用的证据附件（异常转工单时保留的引用），含完整性状态与来源。 */
    @GetMapping("/{id}/attachments")
    public ResponseEntity<?> listAttachments(@PathVariable Long id) {
        if (!repo.existsById(id)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "工单不存在"));
        }
        return ResponseEntity.ok(Map.of(
                "workOrderId", id,
                "evidences", attachmentService.listEvidenceForWorkOrder(id)
        ));
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<?> updateStatus(@PathVariable Long id, @RequestBody StatusRequest req) {
        WorkOrder w = repo.findById(id).orElse(null);
        if (w == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "工单不存在"));
        }
        if (req.status() == null || !STATUSES.contains(req.status())) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", "状态不合法"));
        }
        w.setStatus(req.status());
        if ("done".equals(req.status())) {
            w.setClosedAt(LocalDateTime.now());
        } else {
            w.setClosedAt(null);
        }
        return ResponseEntity.ok(repo.save(w));
    }
}
