package com.admin.equipment.web.inspection;

import com.admin.equipment.model.inspection.InspectionPlan;
import com.admin.equipment.model.inspection.InspectionPlanPoint;
import com.admin.equipment.model.inspection.InspectionPoint;
import com.admin.equipment.service.inspection.InspectionPlanService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/inspection/plans")
public class InspectionPlanController {

    private final InspectionPlanService service;

    public InspectionPlanController(InspectionPlanService service) {
        this.service = service;
    }

    @GetMapping
    public List<InspectionPlan> list(@RequestParam(required = false) Boolean enabled) {
        if (Boolean.TRUE.equals(enabled)) {
            return service.listEnabled();
        }
        return service.listAll();
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable Long id) {
        return service.getById(id)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("detail", "计划不存在")));
    }

    @GetMapping("/{id}/points")
    public ResponseEntity<?> listPoints(@PathVariable Long id,
                                         @RequestParam(defaultValue = "false") boolean detail) {
        if (!service.getById(id).isPresent()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "计划不存在"));
        }
        if (detail) {
            List<InspectionPoint> pts = service.getPlanPointsDetail(id);
            return ResponseEntity.ok(pts);
        } else {
            List<InspectionPlanPoint> pps = service.getPlanPoints(id);
            return ResponseEntity.ok(pps);
        }
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody InspectionPlanService.PlanSpec req) {
        try {
            InspectionPlan p = service.create(req);
            return ResponseEntity.status(HttpStatus.CREATED).body(p);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody InspectionPlanService.PlanSpec req) {
        try {
            InspectionPlan p = service.update(id, req);
            return ResponseEntity.ok(p);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        }
    }

    @PatchMapping("/{id}/enabled")
    public ResponseEntity<?> setEnabled(@PathVariable Long id, @RequestBody Map<String, Boolean> body) {
        try {
            boolean enabled = body.getOrDefault("enabled", true);
            service.setEnabled(id, enabled);
            return service.getById(id)
                    .<ResponseEntity<?>>map(ResponseEntity::ok)
                    .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
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

    @GetMapping("/{id}/route/compare")
    public ResponseEntity<?> compareRoutes(@PathVariable Long id) {
        try {
            InspectionPlanService.RouteCompareResult r = service.compareRoutes(id);
            return ResponseEntity.ok(Map.of(
                    "sequential", r.sequential(),
                    "optimized", r.optimized(),
                    "distanceSaved", r.distanceSaved(),
                    "savedPercent", r.savedPercent(),
                    "savedPercentStr", String.format("%.2f%%", r.savedPercent())
            ));
        } catch (Exception e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        }
    }

    @PostMapping("/{id}/route/plan")
    public ResponseEntity<?> planRoute(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        try {
            Object spObj = body.get("startPointId");
            Long startPointId;
            if (spObj instanceof Number) {
                startPointId = ((Number) spObj).longValue();
            } else {
                startPointId = null;
            }
            boolean useOptimized = !"sequential".equals(body.get("routeType"));
            var r = service.planRouteForExecution(id, startPointId, useOptimized);
            return ResponseEntity.ok(r);
        } catch (Exception e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        }
    }
}
