package com.admin.equipment.seed;

import com.admin.equipment.model.AppUser;
import com.admin.equipment.model.Equipment;
import com.admin.equipment.model.WorkOrder;
import com.admin.equipment.model.inspection.*;
import com.admin.equipment.repo.AppUserRepository;
import com.admin.equipment.repo.EquipmentRepository;
import com.admin.equipment.repo.WorkOrderRepository;
import com.admin.equipment.repo.inspection.*;
import com.admin.equipment.security.PasswordUtil;
import com.admin.equipment.service.inspection.InspectionTemplateService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class DataSeeder implements CommandLineRunner {

    private final AppUserRepository userRepo;
    private final EquipmentRepository equipmentRepo;
    private final WorkOrderRepository workOrderRepo;
    private final InspectionPointRepository pointRepo;
    private final InspectionTemplateRepository templateRepo;
    private final InspectionTemplateItemRepository itemRepo;
    private final InspectionPlanRepository planRepo;
    private final InspectionPlanPointRepository planPointRepo;

    @Value("${app.admin-username}")
    private String adminUsername;

    @Value("${app.admin-password}")
    private String adminPassword;

    public DataSeeder(AppUserRepository userRepo, EquipmentRepository equipmentRepo,
                      WorkOrderRepository workOrderRepo, InspectionPointRepository pointRepo,
                      InspectionTemplateRepository templateRepo,
                      InspectionTemplateItemRepository itemRepo,
                      InspectionPlanRepository planRepo,
                      InspectionPlanPointRepository planPointRepo) {
        this.userRepo = userRepo;
        this.equipmentRepo = equipmentRepo;
        this.workOrderRepo = workOrderRepo;
        this.pointRepo = pointRepo;
        this.templateRepo = templateRepo;
        this.itemRepo = itemRepo;
        this.planRepo = planRepo;
        this.planPointRepo = planPointRepo;
    }

    @Override
    public void run(String... args) {
        seedUsers();
        List<Equipment> equips = seedEquipments();
        seedWorkOrders(equips);
        List<InspectionPoint> points = seedInspectionPoints(equips);
        List<InspectionTemplate> templates = seedTemplates();
        seedPlans(points, templates);
        System.out.println("种子数据初始化完成");
    }

    private void seedUsers() {
        if (!userRepo.existsByUsername(adminUsername)) {
            AppUser admin = new AppUser();
            admin.setUsername(adminUsername);
            admin.setPasswordHash(PasswordUtil.hash(adminPassword));
            admin.setDisplayName("平台管理员");
            userRepo.save(admin);
            System.out.println("已创建管理员账号");
        }
        if (userRepo.count() == 1) {
            String[] names = {"王巡检", "李巡检", "张维保", "赵工程师"};
            String[] usernames = {"wangxj", "lixj", "zhangwb", "zhaogcs"};
            for (int i = 0; i < names.length; i++) {
                if (!userRepo.existsByUsername(usernames[i])) {
                    AppUser u = new AppUser();
                    u.setUsername(usernames[i]);
                    u.setPasswordHash(PasswordUtil.hash("123456"));
                    u.setDisplayName(names[i]);
                    userRepo.save(u);
                }
            }
            System.out.println("已创建巡检/维保人员账号");
        }
    }

    private List<Equipment> seedEquipments() {
        if (equipmentRepo.count() > 0) {
            return equipmentRepo.findAll();
        }
        Equipment e1 = newEquip("EQ-1001", "一号注塑机", "注塑车间A区", "robot", "normal");
        Equipment e2 = newEquip("EQ-1002", "二号空压机", "动力站", "pump", "warning");
        Equipment e3 = newEquip("EQ-1003", "主输送带", "包装车间", "conveyor", "fault");
        Equipment e4 = newEquip("EQ-1004", "冷却循环水泵", "动力站", "pump", "maintenance");
        Equipment e5 = newEquip("EQ-1005", "三号电机组", "电机房", "motor", "normal");
        Equipment e6 = newEquip("EQ-1006", "二号注塑机", "注塑车间B区", "robot", "normal");
        Equipment e7 = newEquip("EQ-1007", "辅助输送带", "包装车间", "conveyor", "normal");
        Equipment e8 = newEquip("EQ-1008", "四号电机", "电机房", "motor", "warning");
        List<Equipment> list = List.of(e1, e2, e3, e4, e5, e6, e7, e8);
        equipmentRepo.saveAll(list);
        return list;
    }

    private void seedWorkOrders(List<Equipment> equips) {
        if (workOrderRepo.count() > 0) return;
        workOrderRepo.saveAll(List.of(
                newOrder(equips.get(1).getId(), "空压机压力异常巡检", "inspection", "high",
                        "巡检发现排气压力波动，需排查", "王巡检", "open"),
                newOrder(equips.get(2).getId(), "输送带断带抢修", "repair", "urgent",
                        "包装线输送带断裂，停机抢修", "李工", "in_progress"),
                newOrder(equips.get(3).getId(), "循环水泵季度保养", "maintenance", "medium",
                        "按计划做季度保养换油", "张工", "open"),
                newOrder(equips.get(0).getId(), "注塑机模具点检", "inspection", "low",
                        "例行模具与液压点检", "赵工", "done")
        ));
    }

    private List<InspectionPoint> seedInspectionPoints(List<Equipment> equips) {
        if (pointRepo.count() > 0) return pointRepo.findAllByOrderByCodeAsc();
        List<InspectionPoint> list = new ArrayList<>();
        InspectionPoint p1 = newPoint("IP-001", "注塑车间A区巡检点", "注塑车间A区东北角",
                10.0, 5.0, equips.get(0).getId().toString(), "robot");
        InspectionPoint p2 = newPoint("IP-002", "动力站空压机巡检点", "动力站北侧",
                25.0, 15.0, equips.get(1).getId().toString(), "pump");
        InspectionPoint p3 = newPoint("IP-003", "包装车间主输送带巡检点", "包装车间中部",
                40.0, 25.0, equips.get(2).getId() + "," + equips.get(6).getId(), "conveyor");
        InspectionPoint p4 = newPoint("IP-004", "动力站冷却水泵巡检点", "动力站南侧",
                25.0, 30.0, equips.get(3).getId().toString(), "pump");
        InspectionPoint p5 = newPoint("IP-005", "电机房三号电机巡检点", "电机房A排",
                55.0, 10.0, equips.get(4).getId().toString(), "motor");
        InspectionPoint p6 = newPoint("IP-006", "注塑车间B区巡检点", "注塑车间B区西南角",
                15.0, 40.0, equips.get(5).getId().toString(), "robot");
        InspectionPoint p7 = newPoint("IP-007", "电机房四号电机巡检点", "电机房B排",
                60.0, 20.0, equips.get(7).getId().toString(), "motor");
        InspectionPoint p8 = newPoint("IP-008", "总控室仪表巡检点", "总控室二楼",
                5.0, 20.0, "", "");
        list.add(p1); list.add(p2); list.add(p3); list.add(p4);
        list.add(p5); list.add(p6); list.add(p7); list.add(p8);
        pointRepo.saveAll(list);
        System.out.println("已初始化巡检点种子数据 (" + list.size() + "个)");
        return list;
    }

    private List<InspectionTemplate> seedTemplates() {
        if (templateRepo.count() > 0) return templateRepo.findAllByOrderByCodeAsc();
        List<InspectionTemplate> list = new ArrayList<>();

        InspectionTemplate t1 = newTemplate("TPL-ROBOT-01", "注塑机（机器人）巡检模板",
                "robot", "注塑设备日常巡检，涵盖液压、温控、模具等方面");
        InspectionTemplate t2 = newTemplate("TPL-PUMP-01", "泵类设备巡检模板",
                "pump", "水泵、空压机等泵类设备通用巡检项");
        InspectionTemplate t3 = newTemplate("TPL-CONV-01", "传送带巡检模板",
                "conveyor", "输送带设备日常巡检标准");
        InspectionTemplate t4 = newTemplate("TPL-MOTOR-01", "电机组巡检模板",
                "motor", "电机设备温度、振动、电气巡检");
        InspectionTemplate t5 = newTemplate("TPL-GEN-01", "通用巡检模板",
                "", "无设备类型绑定的通用检查项");
        list.add(t1); list.add(t2); list.add(t3); list.add(t4); list.add(t5);
        templateRepo.saveAll(list);

        itemRepo.saveAll(List.of(
                newItem(t1.getId(), "液压油位", "option", 1,
                        null, null, "正常,偏高,偏低", "油位在标记线之间为合格"),
                newItem(t1.getId(), "液压油温(℃)", "numeric", 2,
                        35.0, 60.0, "", "35~60度为正常范围"),
                newItem(t1.getId(), "模具状态", "option", 3,
                        null, null, "完好,磨损,损坏", "模具无明显磨损、变形"),
                newItem(t1.getId(), "安全门开关", "option", 4,
                        null, null, "正常,异常", "开合顺畅，感应正常"),
                newItem(t1.getId(), "异常噪声", "option", 5,
                        null, null, "无,轻微,明显", "有明显异响需上报"),

                newItem(t2.getId(), "排气压力(MPa)", "numeric", 1,
                        0.6, 0.8, "", "0.6~0.8 MPa 为正常"),
                newItem(t2.getId(), "运行电流(A)", "numeric", 2,
                        10.0, 45.0, "", "额定电流范围内"),
                newItem(t2.getId(), "振动情况", "option", 3,
                        null, null, "无,轻微,明显", "明显振动需检修"),
                newItem(t2.getId(), "油位/液位", "option", 4,
                        null, null, "正常,偏低,偏高", "液位在刻度范围内"),
                newItem(t2.getId(), "出气管路", "option", 5,
                        null, null, "正常,漏气,堵塞", "管路无漏气堵塞"),

                newItem(t3.getId(), "皮带张紧度", "option", 1,
                        null, null, "合适,过松,过紧", "按下皮带下陷约10mm"),
                newItem(t3.getId(), "皮带磨损", "option", 2,
                        null, null, "正常,轻微,严重", "明显磨损需更换"),
                newItem(t3.getId(), "滚筒转速", "numeric", 3,
                        40.0, 60.0, "", "40-60 rpm 正常"),
                newItem(t3.getId(), "紧急停止", "option", 4,
                        null, null, "有效,失效", "按下可立即停止运行"),
                newItem(t3.getId(), "跑偏情况", "option", 5,
                        null, null, "无,轻微,严重", "严重跑偏需立即调整"),

                newItem(t4.getId(), "定子温度(℃)", "numeric", 1,
                        20.0, 85.0, "", "20~85度正常"),
                newItem(t4.getId(), "轴承温度(℃)", "numeric", 2,
                        20.0, 70.0, "", "20~70度正常"),
                newItem(t4.getId(), "三相电流平衡", "option", 3,
                        null, null, "平衡,轻微偏差,严重偏差", "偏差<10%为合格"),
                newItem(t4.getId(), "绝缘电阻", "option", 4,
                        null, null, "合格,偏低,不合格", "≥0.5MΩ为合格"),
                newItem(t4.getId(), "风扇运转", "option", 5,
                        null, null, "正常,异响,不转", "风扇无异常且转动顺畅"),

                newItem(t5.getId(), "外观完整性", "option", 1,
                        null, null, "完好,轻微破损,严重破损", "设备外观完整"),
                newItem(t5.getId(), "仪表显示", "option", 2,
                        null, null, "正常,异常,不显示", "仪表读数正常可读"),
                newItem(t5.getId(), "环境卫生", "option", 3,
                        null, null, "整洁,一般,较差", "周边环境整洁无杂物")
        ));
        System.out.println("已初始化巡检模板种子数据 (" + list.size() + "个模板)");
        return list;
    }

    private void seedPlans(List<InspectionPoint> points, List<InspectionTemplate> templates) {
        if (planRepo.count() > 0) return;
        InspectionTemplate robotTpl = findTpl(templates, "TPL-ROBOT-01");
        InspectionTemplate pumpTpl = findTpl(templates, "TPL-PUMP-01");
        InspectionTemplate convTpl = findTpl(templates, "TPL-CONV-01");
        InspectionTemplate motorTpl = findTpl(templates, "TPL-MOTOR-01");
        InspectionTemplate genTpl = findTpl(templates, "TPL-GEN-01");

        InspectionPlan plan1 = newPlan("PLAN-DAILY-A", "注塑车间日班巡检计划",
                robotTpl != null ? robotTpl.getId() : templates.get(0).getId(),
                "daily", 1, "day", "08:00", "10:00", 120,
                "甲班巡检组", "2,3", "每日早班对注塑区及周边进行巡检");
        InspectionPlan plan2 = newPlan("PLAN-SHIFT-B", "动力站轮换班巡检计划",
                pumpTpl != null ? pumpTpl.getId() : templates.get(0).getId(),
                "shift", 1, "night", "20:00", "22:00", 180,
                "乙班动力组", "2,4", "夜班动力设备专项巡检");
        InspectionPlan plan3 = newPlan("PLAN-WEEKLY-C", "包装与电机房周巡检计划",
                convTpl != null ? convTpl.getId() : templates.get(0).getId(),
                "weekly", 1, "day", "09:00", "12:00", 240,
                "周巡检组", "2,3,4", "每周一上午执行包装车间和电机房巡检");
        InspectionPlan plan4 = newPlan("PLAN-ALL-D", "全厂综合巡检计划",
                genTpl != null ? genTpl.getId() : templates.get(templates.size() - 1).getId(),
                "daily", 1, "day", "14:00", "17:00", 180,
                "综合巡检组", "2,3,4,5", "每日下午全厂巡检，覆盖所有巡检点");
        planRepo.saveAll(List.of(plan1, plan2, plan3, plan4));

        int seq = 1;
        for (InspectionPoint p : points) {
            if ("robot".equals(p.getEquipmentType()) || "IP-008".equals(p.getCode())) {
                planPointRepo.save(newPlanPoint(plan1.getId(), p.getId(), seq++));
            }
        }
        seq = 1;
        for (InspectionPoint p : points) {
            if ("pump".equals(p.getEquipmentType())) {
                planPointRepo.save(newPlanPoint(plan2.getId(), p.getId(), seq++));
            }
        }
        seq = 1;
        for (InspectionPoint p : points) {
            if ("conveyor".equals(p.getEquipmentType()) || "motor".equals(p.getEquipmentType())) {
                planPointRepo.save(newPlanPoint(plan3.getId(), p.getId(), seq++));
            }
        }
        seq = 1;
        for (InspectionPoint p : points) {
            planPointRepo.save(newPlanPoint(plan4.getId(), p.getId(), seq++));
        }
        System.out.println("已初始化巡检计划种子数据 (4个计划)");
    }

    private Equipment newEquip(String code, String name, String location, String type, String status) {
        Equipment e = new Equipment();
        e.setCode(code);
        e.setName(name);
        e.setLocation(location);
        e.setType(type);
        e.setStatus(status);
        return e;
    }

    private WorkOrder newOrder(Long equipmentId, String title, String type, String priority,
                               String description, String assignee, String status) {
        WorkOrder w = new WorkOrder();
        w.setEquipmentId(equipmentId);
        w.setTitle(title);
        w.setType(type);
        w.setPriority(priority);
        w.setDescription(description);
        w.setAssignee(assignee);
        w.setStatus(status);
        return w;
    }

    private InspectionPoint newPoint(String code, String name, String location,
                                      Double x, Double y, String equipIds, String type) {
        InspectionPoint p = new InspectionPoint();
        p.setCode(code);
        p.setName(name);
        p.setLocation(location);
        p.setCoordX(x);
        p.setCoordY(y);
        p.setEquipmentIds(equipIds);
        p.setEquipmentType(type);
        return p;
    }

    private InspectionTemplate newTemplate(String code, String name, String type, String desc) {
        InspectionTemplate t = new InspectionTemplate();
        t.setCode(code);
        t.setName(name);
        t.setEquipmentType(type);
        t.setDescription(desc);
        return t;
    }

    private InspectionTemplateItem newItem(Long tplId, String name, String type, int sort,
                                            Double min, Double max, String options, String criteria) {
        InspectionTemplateItem i = new InspectionTemplateItem();
        i.setTemplateId(tplId);
        i.setName(name);
        i.setType(type);
        i.setSortOrder(sort);
        i.setNormalMin(min);
        i.setNormalMax(max);
        i.setQualifiedOptions(options);
        i.setJudgeCriteria(criteria);
        return i;
    }

    private InspectionPlan newPlan(String code, String name, Long tplId, String cycle, int cycleVal,
                                    String shift, String start, String end, int winMin,
                                    String team, String asg, String remark) {
        InspectionPlan p = new InspectionPlan();
        p.setCode(code);
        p.setName(name);
        p.setTemplateId(tplId);
        p.setCycleType(cycle);
        p.setCycleValue(cycleVal);
        p.setShiftType(shift);
        p.setStartTime(start);
        p.setEndTime(end);
        p.setTimeWindowMinutes(winMin);
        p.setTeamName(team);
        p.setAssigneeIds(asg);
        p.setRemark(remark);
        p.setEnabled(true);
        return p;
    }

    private InspectionPlanPoint newPlanPoint(Long planId, Long pointId, int seq) {
        InspectionPlanPoint pp = new InspectionPlanPoint();
        pp.setPlanId(planId);
        pp.setPointId(pointId);
        pp.setSequenceNo(seq);
        return pp;
    }

    private InspectionTemplate findTpl(List<InspectionTemplate> list, String code) {
        for (InspectionTemplate t : list) if (code.equals(t.getCode())) return t;
        return null;
    }
}
