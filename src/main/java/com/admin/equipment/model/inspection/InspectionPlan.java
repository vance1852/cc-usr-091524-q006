package com.admin.equipment.model.inspection;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "inspection_plans")
public class InspectionPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 32)
    private String code;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(name = "template_id", nullable = false)
    private Long templateId;

    @Column(length = 16)
    private String cycleType = "daily";

    @Column(name = "cycle_value")
    private Integer cycleValue = 1;

    @Column(name = "shift_type", length = 16)
    private String shiftType = "day";

    @Column(name = "start_time", length = 8)
    private String startTime = "08:00";

    @Column(name = "end_time", length = 8)
    private String endTime = "18:00";

    @Column(name = "time_window_minutes")
    private Integer timeWindowMinutes = 120;

    @Column(name = "team_name", length = 64)
    private String teamName = "";

    @Column(name = "assignee_ids", length = 512)
    private String assigneeIds = "";

    @Column(length = 512)
    private String remark = "";

    @Column
    private Boolean enabled = true;

    @Column(name = "last_generated_at")
    private LocalDateTime lastGeneratedAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Long getTemplateId() { return templateId; }
    public void setTemplateId(Long templateId) { this.templateId = templateId; }
    public String getCycleType() { return cycleType; }
    public void setCycleType(String cycleType) { this.cycleType = cycleType; }
    public Integer getCycleValue() { return cycleValue; }
    public void setCycleValue(Integer cycleValue) { this.cycleValue = cycleValue; }
    public String getShiftType() { return shiftType; }
    public void setShiftType(String shiftType) { this.shiftType = shiftType; }
    public String getStartTime() { return startTime; }
    public void setStartTime(String startTime) { this.startTime = startTime; }
    public String getEndTime() { return endTime; }
    public void setEndTime(String endTime) { this.endTime = endTime; }
    public Integer getTimeWindowMinutes() { return timeWindowMinutes; }
    public void setTimeWindowMinutes(Integer timeWindowMinutes) { this.timeWindowMinutes = timeWindowMinutes; }
    public String getTeamName() { return teamName; }
    public void setTeamName(String teamName) { this.teamName = teamName; }
    public String getAssigneeIds() { return assigneeIds; }
    public void setAssigneeIds(String assigneeIds) { this.assigneeIds = assigneeIds; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public LocalDateTime getLastGeneratedAt() { return lastGeneratedAt; }
    public void setLastGeneratedAt(LocalDateTime lastGeneratedAt) { this.lastGeneratedAt = lastGeneratedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
