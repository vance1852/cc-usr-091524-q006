package com.admin.equipment.service.inspection;

import com.admin.equipment.model.inspection.InspectionPlan;
import com.admin.equipment.model.inspection.InspectionPlanPoint;
import com.admin.equipment.model.inspection.InspectionPoint;
import com.admin.equipment.model.inspection.InspectionTemplate;
import com.admin.equipment.repo.inspection.InspectionPlanPointRepository;
import com.admin.equipment.repo.inspection.InspectionPlanRepository;
import com.admin.equipment.repo.inspection.InspectionPointRepository;
import com.admin.equipment.repo.inspection.InspectionTemplateRepository;
import com.admin.equipment.service.inspection.RoutePlanningService.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class InspectionPlanService {

    private final InspectionPlanRepository planRepo;
    private final InspectionPlanPointRepository planPointRepo;
    private final InspectionTemplateRepository templateRepo;
    private final InspectionPointRepository pointRepo;
    private final RoutePlanningService routeService;

    public InspectionPlanService(InspectionPlanRepository planRepo,
                                  InspectionPlanPointRepository planPointRepo,
                                  InspectionTemplateRepository templateRepo,
                                  InspectionPointRepository pointRepo,
                                  RoutePlanningService routeService) {
        this.planRepo = planRepo;
        this.planPointRepo = planPointRepo;
        this.templateRepo = templateRepo;
        this.pointRepo = pointRepo;
        this.routeService = routeService;
    }

    public List<InspectionPlan> listAll() {
        return planRepo.findAllByOrderByCodeAsc();
    }

    public List<InspectionPlan> listEnabled() {
        return planRepo.findByEnabledTrueOrderByCodeAsc();
    }

    public Optional<InspectionPlan> getById(Long id) {
        return planRepo.findById(id);
    }

    public List<InspectionPlanPoint> getPlanPoints(Long planId) {
        return planPointRepo.findByPlanIdOrderBySequenceNoAsc(planId);
    }

    public List<InspectionPoint> getPlanPointsDetail(Long planId) {
        List<InspectionPlanPoint> pps = planPointRepo.findByPlanIdOrderBySequenceNoAsc(planId);
        List<Long> ids = new ArrayList<>();
        for (InspectionPlanPoint pp : pps) ids.add(pp.getPointId());
        if (ids.isEmpty()) return new ArrayList<>();
        Map<Long, InspectionPoint> map = new HashMap<>();
        for (InspectionPoint p : pointRepo.findByIdIn(ids)) map.put(p.getId(), p);
        List<InspectionPoint> ordered = new ArrayList<>();
        for (Long id : ids) if (map.containsKey(id)) ordered.add(map.get(id));
        return ordered;
    }

    public record PlanSpec(String code, String name, Long templateId, String cycleType, Integer cycleValue,
                           String shiftType, String startTime, String endTime, Integer timeWindowMinutes,
                           String teamName, String assigneeIds, String remark, List<Long> pointIds) {}

    @Transactional
    public InspectionPlan create(PlanSpec spec) {
        if (spec.code() == null || spec.code().isBlank()) throw new IllegalArgumentException("编号必填");
        if (spec.name() == null || spec.name().isBlank()) throw new IllegalArgumentException("名称必填");
        if (spec.templateId() == null) throw new IllegalArgumentException("模板必填");
        if (!templateRepo.existsById(spec.templateId())) throw new IllegalArgumentException("模板不存在");
        if (planRepo.existsByCode(spec.code())) throw new IllegalArgumentException("编号已存在");
        if (spec.pointIds() == null || spec.pointIds().isEmpty()) throw new IllegalArgumentException("至少选择一个巡检点");

        InspectionPlan plan = new InspectionPlan();
        plan.setCode(spec.code());
        plan.setName(spec.name());
        plan.setTemplateId(spec.templateId());
        plan.setCycleType(validCycle(spec.cycleType()));
        plan.setCycleValue(spec.cycleValue() == null ? 1 : Math.max(1, spec.cycleValue()));
        plan.setShiftType(spec.shiftType() == null ? "day" : spec.shiftType());
        plan.setStartTime(spec.startTime() == null ? "08:00" : spec.startTime());
        plan.setEndTime(spec.endTime() == null ? "18:00" : spec.endTime());
        plan.setTimeWindowMinutes(spec.timeWindowMinutes() == null ? 120 : spec.timeWindowMinutes());
        plan.setTeamName(spec.teamName() == null ? "" : spec.teamName());
        plan.setAssigneeIds(spec.assigneeIds() == null ? "" : spec.assigneeIds());
        plan.setRemark(spec.remark() == null ? "" : spec.remark());
        plan.setEnabled(true);
        InspectionPlan saved = planRepo.save(plan);

        int seq = 1;
        for (Long pid : spec.pointIds()) {
            if (!pointRepo.existsById(pid)) throw new IllegalArgumentException("巡检点不存在: " + pid);
            InspectionPlanPoint pp = new InspectionPlanPoint();
            pp.setPlanId(saved.getId());
            pp.setPointId(pid);
            pp.setSequenceNo(seq++);
            planPointRepo.save(pp);
        }
        return saved;
    }

    @Transactional
    public InspectionPlan update(Long id, PlanSpec spec) {
        InspectionPlan plan = planRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("计划不存在"));
        if (spec.name() != null && !spec.name().isBlank()) plan.setName(spec.name());
        if (spec.templateId() != null) {
            if (!templateRepo.existsById(spec.templateId())) throw new IllegalArgumentException("模板不存在");
            plan.setTemplateId(spec.templateId());
        }
        if (spec.cycleType() != null) plan.setCycleType(validCycle(spec.cycleType()));
        if (spec.cycleValue() != null) plan.setCycleValue(Math.max(1, spec.cycleValue()));
        if (spec.shiftType() != null) plan.setShiftType(spec.shiftType());
        if (spec.startTime() != null) plan.setStartTime(spec.startTime());
        if (spec.endTime() != null) plan.setEndTime(spec.endTime());
        if (spec.timeWindowMinutes() != null) plan.setTimeWindowMinutes(spec.timeWindowMinutes());
        if (spec.teamName() != null) plan.setTeamName(spec.teamName());
        if (spec.assigneeIds() != null) plan.setAssigneeIds(spec.assigneeIds());
        if (spec.remark() != null) plan.setRemark(spec.remark());
        if (spec.pointIds() != null && !spec.pointIds().isEmpty()) {
            planPointRepo.deleteByPlanId(id);
            int seq = 1;
            for (Long pid : spec.pointIds()) {
                if (!pointRepo.existsById(pid)) throw new IllegalArgumentException("巡检点不存在: " + pid);
                InspectionPlanPoint pp = new InspectionPlanPoint();
                pp.setPlanId(id);
                pp.setPointId(pid);
                pp.setSequenceNo(seq++);
                planPointRepo.save(pp);
            }
        }
        return planRepo.save(plan);
    }

    @Transactional
    public void setEnabled(Long id, boolean enabled) {
        InspectionPlan plan = planRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("计划不存在"));
        plan.setEnabled(enabled);
        planRepo.save(plan);
    }

    @Transactional
    public void delete(Long id) {
        if (!planRepo.existsById(id)) throw new IllegalArgumentException("计划不存在");
        planPointRepo.deleteByPlanId(id);
        planRepo.deleteById(id);
    }

    public RouteCompareResult compareRoutes(Long planId) {
        List<InspectionPoint> points = getPlanPointsDetail(planId);
        if (points.size() < 2) {
            List<RoutePoint> single = new ArrayList<>();
            if (!points.isEmpty()) {
                InspectionPoint p = points.get(0);
                RoutePoint rp = new RoutePoint(p.getId(), p.getCode(), p.getName(), p.getCoordX(), p.getCoordY());
                rp.sequence = 1;
                single.add(rp);
            }
            return new RouteCompareResult(
                    new RouteResult(single, 0.0, "sequential"),
                    new RouteResult(single, 0.0, "optimized"),
                    0.0, 0.0
            );
        }
        RouteResult seq = routeService.planByCodeOrder(points);
        RouteResult opt = routeService.planOptimizedTSP(points);
        double saved = seq.totalDistance - opt.totalDistance;
        double ratio = seq.totalDistance > 0 ? (saved / seq.totalDistance * 100) : 0;
        return new RouteCompareResult(seq, opt, saved, ratio);
    }

    public record RouteCompareResult(RouteResult sequential, RouteResult optimized,
                                      double distanceSaved, double savedPercent) {}

    public RouteResult planRouteForExecution(Long planId, Long startPointId, boolean useOptimized) {
        List<InspectionPoint> points = getPlanPointsDetail(planId);
        if (points.isEmpty()) {
            return new RouteResult(new ArrayList<>(), 0.0, useOptimized ? "optimized" : "sequential");
        }
        return useOptimized ? routeService.planOptimizedTSP(points, startPointId)
                            : routeService.planByCodeOrder(points);
    }

    private String validCycle(String c) {
        if (c == null) return "daily";
        return switch (c) {
            case "daily", "weekly", "monthly", "shift", "hourly" -> c;
            default -> "daily";
        };
    }
}
