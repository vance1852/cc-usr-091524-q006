package com.admin.equipment.web.inspection;

import com.admin.equipment.service.inspection.InspectionStatsService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/inspection/stats")
public class InspectionStatsController {

    private final InspectionStatsService service;

    public InspectionStatsController(InspectionStatsService service) {
        this.service = service;
    }

    @GetMapping("/overview")
    public ResponseEntity<?> overview() {
        return ResponseEntity.ok(service.getOverallStats());
    }

    @GetMapping("/date-range")
    public ResponseEntity<?> dateRange(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        if (startDate.isAfter(endDate)) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", "开始日期不能晚于结束日期"));
        }
        List<InspectionStatsService.DateStats> stats = service.getDateRangeStats(startDate, endDate);
        return ResponseEntity.ok(stats);
    }

    @GetMapping("/tasks/completion")
    public ResponseEntity<?> taskCompletion(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        if (startDate.isAfter(endDate)) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", "开始日期不能晚于结束日期"));
        }
        return ResponseEntity.ok(service.getTaskCompletionStats(startDate, endDate));
    }

    @GetMapping("/equipment/{equipmentId}/history")
    public ResponseEntity<?> equipmentHistory(@PathVariable Long equipmentId) {
        return ResponseEntity.ok(service.getEquipmentHistory(equipmentId));
    }

    @GetMapping("/closed-loop")
    public ResponseEntity<?> closedLoop(@RequestParam(required = false) String status) {
        return ResponseEntity.ok(service.getClosedLoopTraces(status));
    }

    @GetMapping("/tasks/{taskId}/trace")
    public ResponseEntity<?> executionTrace(@PathVariable Long taskId) {
        return ResponseEntity.ok(service.getExecutionTrace(taskId));
    }
}
