package com.admin.equipment.web.inspection;

import com.admin.equipment.model.WorkOrder;
import com.admin.equipment.model.inspection.*;
import com.admin.equipment.service.inspection.InspectionTaskService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/inspection/tasks")
public class InspectionTaskController {

    private final InspectionTaskService service;

    public InspectionTaskController(InspectionTaskService service) {
        this.service = service;
    }

    public record GenerateRequest(Long planId, Long assigneeId, String assigneeName,
                                   Boolean useOptimizedRoute, Long startPointId) {}

    public record StartRequest(String inspectorName) {}

    public record ExecuteRequest(Long taskId, Long taskPointId, String inspectorName,
                                  List<InspectionTaskService.PointItemSpec> items, String remark) {}

    public record SkipRequest(Long taskId, Long taskPointId, String reason) {}

    public record ReportAbnormalRequest(Long taskId, Long taskPointId, Long recordId, Long equipmentId,
                                         String title, String description, String severity, String workOrderType) {}

    public record RecheckRequest(Long abnormalityId, String result, String recheckBy) {}

    @GetMapping
    public List<InspectionTask> list(@RequestParam(required = false) Long planId,
                                      @RequestParam(required = false) String status,
                                      @RequestParam(required = false) Long assigneeId) {
        if (planId != null) return service.listByPlan(planId);
        if (status != null && !status.isBlank()) return service.listByStatus(status);
        if (assigneeId != null) return service.listByAssignee(assigneeId);
        return service.listAll();
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable Long id) {
        return service.getById(id)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("detail", "任务不存在")));
    }

    @GetMapping("/{id}/points")
    public ResponseEntity<?> listPoints(@PathVariable Long id,
                                         @RequestParam(defaultValue = "planned") String order) {
        if (!service.getById(id).isPresent()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "任务不存在"));
        }
        List<InspectionTaskPoint> tps = "actual".equals(order)
                ? service.getTaskPointsByActualOrder(id)
                : service.getTaskPoints(id);
        return ResponseEntity.ok(tps);
    }

    @GetMapping("/{id}/records")
    public ResponseEntity<?> listRecords(@PathVariable Long id) {
        if (!service.getById(id).isPresent()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "任务不存在"));
        }
        return ResponseEntity.ok(service.getTaskRecords(id));
    }

    @GetMapping("/points/{taskPointId}/records")
    public ResponseEntity<?> listPointRecords(@PathVariable Long taskPointId) {
        return ResponseEntity.ok(service.getPointRecords(taskPointId));
    }

    @GetMapping("/{id}/abnormalities")
    public ResponseEntity<?> listAbnormalities(@PathVariable Long id) {
        if (!service.getById(id).isPresent()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "任务不存在"));
        }
        return ResponseEntity.ok(service.getTaskAbnormalities(id));
    }

    @PostMapping("/generate")
    public ResponseEntity<?> generate(@RequestBody GenerateRequest req) {
        try {
            if (req.planId() == null) {
                return ResponseEntity.unprocessableEntity().body(Map.of("detail", "计划ID必填"));
            }
            boolean useOpt = req.useOptimizedRoute() == null || Boolean.TRUE.equals(req.useOptimizedRoute());
            InspectionTask t = service.generateTask(req.planId(), req.assigneeId(),
                    req.assigneeName(), useOpt, req.startPointId());
            return ResponseEntity.status(HttpStatus.CREATED).body(t);
        } catch (Exception e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        }
    }

    @PostMapping("/{id}/start")
    public ResponseEntity<?> start(@PathVariable Long id, @RequestBody(required = false) StartRequest req) {
        try {
            String name = req != null ? req.inspectorName() : null;
            InspectionTask t = service.startTask(id, name);
            return ResponseEntity.ok(t);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        }
    }

    @PostMapping("/execute")
    public ResponseEntity<?> executePoint(@RequestBody ExecuteRequest req) {
        try {
            if (req.taskId() == null || req.taskPointId() == null) {
                return ResponseEntity.unprocessableEntity().body(Map.of("detail", "任务ID和巡检点ID必填"));
            }
            InspectionTaskService.PointExecuteResult r = service.executePoint(
                    req.taskId(), req.taskPointId(), req.inspectorName(), req.items(), req.remark());
            return ResponseEntity.ok(Map.of(
                    "taskPoint", r.taskPoint(),
                    "records", r.records(),
                    "abnormalities", r.abnormalities(),
                    "createdWorkOrders", r.createdWorkOrders()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        }
    }

    @PostMapping("/skip")
    public ResponseEntity<?> skipPoint(@RequestBody SkipRequest req) {
        try {
            if (req.taskId() == null || req.taskPointId() == null) {
                return ResponseEntity.unprocessableEntity().body(Map.of("detail", "任务ID和巡检点ID必填"));
            }
            InspectionTask t = service.skipPoint(req.taskId(), req.taskPointId(), req.reason());
            return ResponseEntity.ok(t);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        }
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<?> cancel(@PathVariable Long id) {
        try {
            InspectionTask t = service.cancelTask(id);
            return ResponseEntity.ok(t);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        }
    }

    @PostMapping("/detect-missed-timeout")
    public ResponseEntity<?> detectMissedAndTimeout() {
        service.detectMissedAndTimeout();
        return ResponseEntity.ok(Map.of("result", "已执行漏检与超时检测"));
    }

    @PostMapping("/abnormality/report")
    public ResponseEntity<?> reportAbnormality(@RequestBody ReportAbnormalRequest req) {
        try {
            if (req.taskId() == null) {
                return ResponseEntity.unprocessableEntity().body(Map.of("detail", "任务ID必填"));
            }
            InspectionAbnormality ab = service.reportAbnormality(req.taskId(), req.taskPointId(),
                    req.recordId(), req.equipmentId(), req.title(), req.description(),
                    req.severity(), req.workOrderType());
            return ResponseEntity.status(HttpStatus.CREATED).body(ab);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        }
    }

    @PostMapping("/abnormality/recheck")
    public ResponseEntity<?> recheckAbnormality(@RequestBody RecheckRequest req) {
        try {
            if (req.abnormalityId() == null) {
                return ResponseEntity.unprocessableEntity().body(Map.of("detail", "异常ID必填"));
            }
            InspectionAbnormality ab = service.recheckAbnormality(req.abnormalityId(),
                    req.result(), req.recheckBy());
            return ResponseEntity.ok(ab);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        }
    }
}
