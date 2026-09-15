package com.admin.equipment.service.inspection;

import com.admin.equipment.model.Equipment;
import com.admin.equipment.model.WorkOrder;
import com.admin.equipment.model.inspection.*;
import com.admin.equipment.repo.EquipmentRepository;
import com.admin.equipment.repo.WorkOrderRepository;
import com.admin.equipment.repo.inspection.*;
import com.admin.equipment.service.inspection.InspectionTemplateService.JudgeResult;
import com.admin.equipment.service.inspection.RoutePlanningService.RoutePoint;
import com.admin.equipment.service.inspection.RoutePlanningService.RouteResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class InspectionTaskService {

    private final InspectionTaskRepository taskRepo;
    private final InspectionTaskPointRepository taskPointRepo;
    private final InspectionRecordRepository recordRepo;
    private final InspectionAbnormalityRepository abnormalityRepo;
    private final InspectionPlanRepository planRepo;
    private final InspectionTemplateRepository templateRepo;
    private final InspectionTemplateItemRepository templateItemRepo;
    private final InspectionPointRepository pointRepo;
    private final EquipmentRepository equipmentRepo;
    private final WorkOrderRepository workOrderRepo;
    private final InspectionTemplateService templateService;
    private final InspectionPlanService planService;

    public InspectionTaskService(InspectionTaskRepository taskRepo,
                                 InspectionTaskPointRepository taskPointRepo,
                                 InspectionRecordRepository recordRepo,
                                 InspectionAbnormalityRepository abnormalityRepo,
                                 InspectionPlanRepository planRepo,
                                 InspectionTemplateRepository templateRepo,
                                 InspectionTemplateItemRepository templateItemRepo,
                                 InspectionPointRepository pointRepo,
                                 EquipmentRepository equipmentRepo,
                                 WorkOrderRepository workOrderRepo,
                                 InspectionTemplateService templateService,
                                 InspectionPlanService planService) {
        this.taskRepo = taskRepo;
        this.taskPointRepo = taskPointRepo;
        this.recordRepo = recordRepo;
        this.abnormalityRepo = abnormalityRepo;
        this.planRepo = planRepo;
        this.templateRepo = templateRepo;
        this.templateItemRepo = templateItemRepo;
        this.pointRepo = pointRepo;
        this.equipmentRepo = equipmentRepo;
        this.workOrderRepo = workOrderRepo;
        this.templateService = templateService;
        this.planService = planService;
    }

    public List<InspectionTask> listAll() {
        return taskRepo.findAllByOrderByCreatedAtDesc();
    }

    public List<InspectionTask> listByPlan(Long planId) {
        return taskRepo.findByPlanIdOrderByCreatedAtDesc(planId);
    }

    public List<InspectionTask> listByStatus(String status) {
        return taskRepo.findByStatusOrderByCreatedAtDesc(status);
    }

    public List<InspectionTask> listByAssignee(Long assigneeId) {
        return taskRepo.findByAssigneeIdOrderByCreatedAtDesc(assigneeId);
    }

    public Optional<InspectionTask> getById(Long id) {
        return taskRepo.findById(id);
    }

    public List<InspectionTaskPoint> getTaskPoints(Long taskId) {
        return taskPointRepo.findByTaskIdOrderByPlannedSequenceAsc(taskId);
    }

    public List<InspectionTaskPoint> getTaskPointsByActualOrder(Long taskId) {
        return taskPointRepo.findByTaskIdOrderByActualSequenceAsc(taskId);
    }

    public List<InspectionRecord> getTaskRecords(Long taskId) {
        return recordRepo.findByTaskIdOrderByIdAsc(taskId);
    }

    public List<InspectionRecord> getPointRecords(Long taskPointId) {
        return recordRepo.findByTaskPointIdOrderByIdAsc(taskPointId);
    }

    public List<InspectionAbnormality> getTaskAbnormalities(Long taskId) {
        return abnormalityRepo.findByTaskIdOrderByReportedAtDesc(taskId);
    }

    @Transactional
    public InspectionTask generateTask(Long planId, Long assigneeId, String assigneeName,
                                        boolean useOptimizedRoute, Long startPointId) {
        InspectionPlan plan = planRepo.findById(planId)
                .orElseThrow(() -> new IllegalArgumentException("巡检计划不存在"));

        if (Boolean.FALSE.equals(plan.getEnabled())) {
            throw new IllegalArgumentException("该计划已禁用");
        }

        InspectionTemplate template = templateRepo.findById(plan.getTemplateId())
                .orElseThrow(() -> new IllegalArgumentException("计划模板不存在"));

        LocalDateTime now = LocalDateTime.now();
        LocalDate today = now.toLocalDate();
        LocalDateTime winStart = parseTime(today, plan.getStartTime());
        LocalDateTime winEnd;
        if (plan.getTimeWindowMinutes() != null) {
            winEnd = winStart.plusMinutes(plan.getTimeWindowMinutes());
        } else {
            winEnd = parseTime(today, plan.getEndTime());
        }

        String code = "TK-" + plan.getCode() + "-" + now.format(DateTimeFormatter.ofPattern("yyyyMMddHHmm"));

        InspectionTask task = new InspectionTask();
        task.setPlanId(planId);
        task.setCode(code);
        task.setTemplateId(plan.getTemplateId());
        task.setStatus("pending");
        task.setScheduledStart(winStart);
        task.setScheduledEnd(winEnd);
        task.setAssigneeId(assigneeId);
        task.setAssigneeName(assigneeName == null ? "" : assigneeName);
        task.setTeamName(plan.getTeamName());
        task.setRouteType(useOptimizedRoute ? "optimized" : "sequential");

        RouteResult route = planService.planRouteForExecution(planId, startPointId, useOptimizedRoute);
        InspectionPlanService.RouteCompareResult compare = planService.compareRoutes(planId);

        task.setRouteDistance(route.totalDistance);
        task.setSequentialDistance(compare.sequential().totalDistance);
        task.setOptimizedDistance(compare.optimized().totalDistance);
        task.setDistanceSaved(compare.distanceSaved());

        List<RoutePoint> ordered = route.orderedPoints;
        task.setTotalPoints(ordered.size());
        task.setCompletedPoints(0);
        task.setMissedPoints(0);
        task.setAbnormalCount(0);

        InspectionTask savedTask = taskRepo.save(task);

        int seq = 1;
        for (RoutePoint rp : ordered) {
            Optional<InspectionPoint> pOpt = pointRepo.findById(rp.pointId);
            if (pOpt.isEmpty()) continue;
            InspectionPoint p = pOpt.get();
            long itemCnt = templateItemRepo.countByTemplateId(plan.getTemplateId());
            InspectionTaskPoint tp = new InspectionTaskPoint();
            tp.setTaskId(savedTask.getId());
            tp.setPointId(p.getId());
            tp.setPointCode(p.getCode());
            tp.setPointName(p.getName());
            tp.setPointLocation(p.getLocation());
            tp.setPlannedSequence(seq++);
            tp.setCoordX(p.getCoordX());
            tp.setCoordY(p.getCoordY());
            tp.setEquipmentIds(p.getEquipmentIds());
            tp.setItemCount((int) itemCnt);
            tp.setQualifiedCount(0);
            tp.setAbnormalCount(0);
            tp.setIsMissed(false);
            tp.setStatus("pending");
            taskPointRepo.save(tp);
        }

        plan.setLastGeneratedAt(now);
        planRepo.save(plan);
        return savedTask;
    }

    @Transactional
    public InspectionTask startTask(Long taskId, String inspectorName) {
        InspectionTask task = taskRepo.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在"));
        if (!"pending".equals(task.getStatus())) {
            throw new IllegalArgumentException("任务状态不合法，当前：" + task.getStatus());
        }
        task.setStatus("in_progress");
        task.setActualStart(LocalDateTime.now());
        if (inspectorName != null && !inspectorName.isBlank()) {
            task.setAssigneeName(inspectorName);
        }
        return taskRepo.save(task);
    }

    public record PointItemSpec(Long templateItemId, String checkValue, Double checkNumeric, String remark) {}

    @Transactional
    public PointExecuteResult executePoint(Long taskId, Long taskPointId, String inspectorName,
                                            List<PointItemSpec> items, String remark) {
        InspectionTask task = taskRepo.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在"));
        if (!"in_progress".equals(task.getStatus())) {
            throw new IllegalArgumentException("任务未开始");
        }
        InspectionTaskPoint tp = taskPointRepo.findById(taskPointId)
                .orElseThrow(() -> new IllegalArgumentException("任务巡检点不存在"));
        if (!tp.getTaskId().equals(taskId)) {
            throw new IllegalArgumentException("巡检点不属于该任务");
        }
        if ("completed".equals(tp.getStatus())) {
            throw new IllegalArgumentException("该巡检点已完成，禁止重复巡检");
        }

        LocalDateTime now = LocalDateTime.now();
        boolean isFirst = tp.getArrivedAt() == null;
        if (isFirst) {
            tp.setArrivedAt(now);
            int actualSeq = countCompletedPoints(taskId) + 1;
            tp.setActualSequence(actualSeq);
        }
        tp.setStatus("in_progress");
        tp.setInspectorName(inspectorName == null ? "" : inspectorName);
        if (remark != null) tp.setRemark(remark);

        List<InspectionTemplateItem> templateItems = templateItemRepo.findByTemplateIdOrderBySortOrderAsc(task.getTemplateId());
        Map<Long, InspectionTemplateItem> itemMap = new HashMap<>();
        for (InspectionTemplateItem ti : templateItems) itemMap.put(ti.getId(), ti);

        int qualifiedCnt = 0;
        int abnormalCnt = 0;
        List<InspectionRecord> savedRecords = new ArrayList<>();
        List<InspectionAbnormality> newAbnormalities = new ArrayList<>();
        List<WorkOrder> createdOrders = new ArrayList<>();

        if (items != null) {
            for (PointItemSpec spec : items) {
                InspectionTemplateItem ti = itemMap.get(spec.templateItemId());
                if (ti == null) continue;
                JudgeResult jr = templateService.judgeItem(ti, spec.checkValue(), spec.checkNumeric());
                InspectionRecord rec = new InspectionRecord();
                rec.setTaskId(taskId);
                rec.setTaskPointId(taskPointId);
                rec.setPointId(tp.getPointId());
                rec.setTemplateItemId(ti.getId());
                rec.setItemName(ti.getName());
                rec.setItemType(ti.getType());
                rec.setCheckValue(spec.checkValue() == null ? "" : spec.checkValue());
                rec.setCheckNumeric(jr.numericValue());
                rec.setIsQualified(jr.qualified());
                rec.setIsAbnormal(!jr.qualified());
                rec.setJudgeDetail(jr.detail());
                rec.setRecordedAt(now);
                rec.setRecordedBy(inspectorName == null ? "" : inspectorName);
                rec.setRemark(spec.remark() == null ? "" : spec.remark());
                InspectionRecord savedRec = recordRepo.save(rec);
                savedRecords.add(savedRec);

                if (jr.qualified()) {
                    qualifiedCnt++;
                } else {
                    abnormalCnt++;
                    List<Long> equipIds = parseIds(tp.getEquipmentIds());
                    if (equipIds.isEmpty()) {
                        InspectionAbnormality ab = createAbnormalityIfAbsent(
                                task, tp, savedRec, tp.getPointId(), null, null, ti, spec);
                        if (ab != null) {
                            newAbnormalities.add(ab);
                        }
                    } else {
                        for (Long eid : equipIds) {
                            Equipment eq = equipmentRepo.findById(eid).orElse(null);
                            InspectionAbnormality ab = createAbnormalityIfAbsent(
                                    task, tp, savedRec, tp.getPointId(), eid, eq, ti, spec);
                            if (ab != null) {
                                newAbnormalities.add(ab);
                                if (ab.getWorkOrderCreated() && ab.getWorkOrderId() != null) {
                                    workOrderRepo.findById(ab.getWorkOrderId()).ifPresent(createdOrders::add);
                                }
                            }
                        }
                    }
                }
            }
        }

        tp.setLeftAt(now);
        if (tp.getArrivedAt() != null) {
            long dur = Duration.between(tp.getArrivedAt(), now).getSeconds();
            tp.setDurationSeconds(dur);
        }
        tp.setItemCount(Math.max(templateItems.size(), tp.getItemCount() == null ? 0 : tp.getItemCount()));
        tp.setQualifiedCount(qualifiedCnt);
        tp.setAbnormalCount(abnormalCnt);
        tp.setIsMissed(false);
        tp.setStatus("completed");
        taskPointRepo.save(tp);

        int completedPts = countCompletedPoints(taskId);
        task.setCompletedPoints(completedPts);
        task.setAbnormalCount((task.getAbnormalCount() == null ? 0 : task.getAbnormalCount()) + abnormalCnt);
        if (completedPts >= (task.getTotalPoints() == null ? 0 : task.getTotalPoints())
                && "in_progress".equals(task.getStatus())) {
            task.setStatus("completed");
            task.setActualEnd(now);
        }
        taskRepo.save(task);

        return new PointExecuteResult(tp, savedRecords, newAbnormalities, createdOrders);
    }

    public record PointExecuteResult(InspectionTaskPoint taskPoint,
                                     List<InspectionRecord> records,
                                     List<InspectionAbnormality> abnormalities,
                                     List<WorkOrder> createdWorkOrders) {}

    @Transactional
    public InspectionTask skipPoint(Long taskId, Long taskPointId, String reason) {
        InspectionTask task = taskRepo.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在"));
        InspectionTaskPoint tp = taskPointRepo.findById(taskPointId)
                .orElseThrow(() -> new IllegalArgumentException("巡检点不存在"));
        if (!tp.getTaskId().equals(taskId)) {
            throw new IllegalArgumentException("巡检点不属于该任务");
        }
        tp.setStatus("skipped");
        tp.setIsMissed(true);
        tp.setRemark(reason == null ? "跳过" : reason);
        taskPointRepo.save(tp);
        int missed = (int) taskPointRepo.countByTaskIdAndIsMissedTrue(taskId);
        task.setMissedPoints(missed);
        return taskRepo.save(task);
    }

    @Transactional
    public InspectionTask cancelTask(Long taskId) {
        InspectionTask task = taskRepo.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在"));
        task.setStatus("cancelled");
        return taskRepo.save(task);
    }

    @Transactional
    public void detectMissedAndTimeout() {
        LocalDateTime now = LocalDateTime.now();
        List<InspectionTask> inProgress = taskRepo.findByStatusOrderByCreatedAtDesc("in_progress");
        for (InspectionTask task : inProgress) {
            if (task.getScheduledEnd() != null && now.isAfter(task.getScheduledEnd())) {
                if (!Boolean.TRUE.equals(task.getTimeoutWarned())) {
                    task.setTimeoutWarned(true);
                    taskRepo.save(task);
                }
            }
        }
        List<InspectionTask> allTasks = taskRepo.findAll();
        for (InspectionTask task : allTasks) {
            if ("pending".equals(task.getStatus()) && task.getScheduledEnd() != null
                    && now.isAfter(task.getScheduledEnd().plusHours(24))) {
                List<InspectionTaskPoint> tps = taskPointRepo.findByTaskIdOrderByPlannedSequenceAsc(task.getId());
                int missed = 0;
                for (InspectionTaskPoint tp : tps) {
                    if (!"completed".equals(tp.getStatus())) {
                        tp.setIsMissed(true);
                        tp.setStatus("skipped");
                        if (tp.getRemark() == null || tp.getRemark().isEmpty()) {
                            tp.setRemark("超时未巡检，系统标记漏检");
                        }
                        taskPointRepo.save(tp);
                        missed++;
                    }
                }
                task.setMissedPoints(missed);
                task.setStatus("completed");
                task.setActualEnd(now);
                taskRepo.save(task);
            }
        }
    }

    @Transactional
    public InspectionAbnormality reportAbnormality(Long taskId, Long taskPointId, Long recordId, Long equipmentId,
                                                    String title, String description, String severity, String workOrderType) {
        InspectionTask task = taskRepo.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在"));
        InspectionTaskPoint tp = null;
        if (taskPointId != null) {
            tp = taskPointRepo.findById(taskPointId).orElse(null);
        }
        InspectionRecord rec = null;
        if (recordId != null) {
            rec = recordRepo.findById(recordId).orElse(null);
        }
        Equipment eq = null;
        if (equipmentId != null) {
            eq = equipmentRepo.findById(equipmentId).orElse(null);
        }
        InspectionAbnormality ab = new InspectionAbnormality();
        ab.setTaskId(taskId);
        ab.setTaskPointId(taskPointId);
        ab.setRecordId(recordId);
        ab.setPointId(tp != null ? tp.getPointId() : null);
        ab.setEquipmentId(equipmentId);
        ab.setEquipmentCode(eq != null ? eq.getCode() : "");
        ab.setEquipmentName(eq != null ? eq.getName() : "");
        ab.setItemName(rec != null ? rec.getItemName() : "");
        ab.setTitle(title == null ? "巡检异常上报" : title);
        ab.setDescription(description == null ? "" : description);
        ab.setSeverity(validSeverity(severity));
        ab.setStatus("reported");
        ab.setWorkOrderType(validWOType(workOrderType));
        InspectionAbnormality saved = abnormalityRepo.save(ab);
        WorkOrder wo = autoConvertToWorkOrder(saved, eq);
        if (wo != null) {
            saved.setWorkOrderId(wo.getId());
            saved.setWorkOrderCreated(true);
            saved.setStatus("wo_created");
            saved = abnormalityRepo.save(saved);
            if (tp != null) {
                tp.setAbnormalCount((tp.getAbnormalCount() == null ? 0 : tp.getAbnormalCount()) + 1);
                taskPointRepo.save(tp);
            }
        }
        return saved;
    }

    @Transactional
    public InspectionAbnormality recheckAbnormality(Long abnormalityId, String result, String recheckBy) {
        InspectionAbnormality ab = abnormalityRepo.findById(abnormalityId)
                .orElseThrow(() -> new IllegalArgumentException("异常不存在"));
        ab.setRecheckResult(result);
        ab.setRecheckAt(LocalDateTime.now());
        ab.setRecheckBy(recheckBy == null ? "" : recheckBy);
        if ("passed".equals(result)) {
            ab.setStatus("resolved");
            ab.setClosedLoop(true);
            ab.setResolvedAt(LocalDateTime.now());
        } else if ("failed".equals(result)) {
            ab.setStatus("recheck_failed");
        } else {
            ab.setStatus("rechecked");
        }
        return abnormalityRepo.save(ab);
    }

    private InspectionAbnormality createAbnormalityIfAbsent(InspectionTask task, InspectionTaskPoint tp,
                                                             InspectionRecord rec, Long pointId, Long equipmentId,
                                                             Equipment eq, InspectionTemplateItem item, PointItemSpec spec) {
        if (equipmentId != null) {
            Optional<InspectionAbnormality> existsOpt = abnormalityRepo
                    .findByTaskPointIdAndRecordIdAndEquipmentId(tp.getId(), rec.getId(), equipmentId);
            if (existsOpt.isPresent()) return null;
        }
        InspectionAbnormality ab = new InspectionAbnormality();
        ab.setTaskId(task.getId());
        ab.setTaskPointId(tp.getId());
        ab.setRecordId(rec.getId());
        ab.setPointId(pointId);
        ab.setEquipmentId(equipmentId);
        ab.setEquipmentCode(eq != null ? eq.getCode() : "");
        ab.setEquipmentName(eq != null ? eq.getName() : "");
        ab.setItemName(item.getName());
        ab.setTitle("巡检异常-" + item.getName());
        String desc = "巡检项[" + item.getName() + "] 不合格。判定：" + rec.getJudgeDetail();
        if (spec != null && spec.remark() != null && !spec.remark().isEmpty()) {
            desc += " 备注：" + spec.remark();
        }
        ab.setDescription(desc);
        ab.setSeverity("medium");
        ab.setStatus("reported");
        ab.setWorkOrderType("repair");
        InspectionAbnormality saved = abnormalityRepo.save(ab);
        if (eq != null) {
            WorkOrder wo = autoConvertToWorkOrder(saved, eq);
            if (wo != null) {
                saved.setWorkOrderId(wo.getId());
                saved.setWorkOrderCreated(true);
                saved.setStatus("wo_created");
                saved = abnormalityRepo.save(saved);
            }
        }
        return saved;
    }

    private WorkOrder autoConvertToWorkOrder(InspectionAbnormality ab, Equipment eq) {
        if (eq == null || ab.getEquipmentId() == null) return null;
        if (Boolean.TRUE.equals(ab.getWorkOrderCreated()) || ab.getWorkOrderId() != null) return null;
        if (abnormalityRepo.existsByWorkOrderId(ab.getId())) return null;
        WorkOrder wo = new WorkOrder();
        wo.setEquipmentId(ab.getEquipmentId());
        String woType = ab.getWorkOrderType();
        if (woType == null) woType = "repair";
        wo.setType(woType);
        wo.setTitle("[巡检转工单] " + ab.getTitle());
        String pri = ab.getSeverity();
        if ("urgent".equals(pri)) wo.setPriority("urgent");
        else if ("high".equals(pri)) wo.setPriority("high");
        else wo.setPriority("medium");
        wo.setDescription("来源：巡检异常\n异常ID:" + ab.getId() + "\n任务ID:" + ab.getTaskId() + "\n" + ab.getDescription());
        wo.setAssignee("");
        wo.setStatus("open");
        return workOrderRepo.save(wo);
    }

    private int countCompletedPoints(Long taskId) {
        return (int) taskPointRepo.countByTaskIdAndStatus(taskId, "completed");
    }

    private LocalDateTime parseTime(LocalDate date, String timeStr) {
        if (timeStr == null || timeStr.isBlank()) {
            return date.atTime(8, 0);
        }
        try {
            String[] parts = timeStr.split(":");
            int h = Integer.parseInt(parts[0]);
            int m = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            return date.atTime(h, m);
        } catch (Exception e) {
            return date.atTime(8, 0);
        }
    }

    private List<Long> parseIds(String str) {
        List<Long> result = new ArrayList<>();
        if (str == null || str.isBlank()) return result;
        String[] parts = str.split(",");
        for (String p : parts) {
            try {
                Long id = Long.parseLong(p.trim());
                if (id > 0) result.add(id);
            } catch (NumberFormatException ignored) {}
        }
        return result;
    }

    private String validSeverity(String s) {
        if (s == null) return "medium";
        return switch (s) {
            case "low", "medium", "high", "urgent" -> s;
            default -> "medium";
        };
    }

    private String validWOType(String s) {
        if (s == null) return "repair";
        return switch (s) {
            case "repair", "maintenance", "inspection" -> s;
            default -> "repair";
        };
    }
}
