package com.admin.equipment.model.inspection;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "inspection_tasks")
public class InspectionTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "plan_id", nullable = false)
    private Long planId;

    @Column(nullable = false, length = 64)
    private String code;

    @Column(name = "template_id", nullable = false)
    private Long templateId;

    @Column(length = 16)
    private String status = "pending";

    @Column(name = "scheduled_start")
    private LocalDateTime scheduledStart;

    @Column(name = "scheduled_end")
    private LocalDateTime scheduledEnd;

    @Column(name = "actual_start")
    private LocalDateTime actualStart;

    @Column(name = "actual_end")
    private LocalDateTime actualEnd;

    @Column(name = "assignee_id")
    private Long assigneeId;

    @Column(name = "assignee_name", length = 64)
    private String assigneeName = "";

    @Column(name = "team_name", length = 64)
    private String teamName = "";

    @Column(name = "total_points")
    private Integer totalPoints = 0;

    @Column(name = "completed_points")
    private Integer completedPoints = 0;

    @Column(name = "missed_points")
    private Integer missedPoints = 0;

    @Column(name = "abnormal_count")
    private Integer abnormalCount = 0;

    @Column(name = "timeout_warned")
    private Boolean timeoutWarned = false;

    @Column(name = "route_type", length = 16)
    private String routeType = "optimized";

    @Column(name = "route_distance")
    private Double routeDistance = 0.0;

    @Column(name = "sequential_distance")
    private Double sequentialDistance = 0.0;

    @Column(name = "optimized_distance")
    private Double optimizedDistance = 0.0;

    @Column(name = "distance_saved")
    private Double distanceSaved = 0.0;

    @Column(length = 1024)
    private String remark = "";

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getPlanId() { return planId; }
    public void setPlanId(Long planId) { this.planId = planId; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public Long getTemplateId() { return templateId; }
    public void setTemplateId(Long templateId) { this.templateId = templateId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getScheduledStart() { return scheduledStart; }
    public void setScheduledStart(LocalDateTime scheduledStart) { this.scheduledStart = scheduledStart; }
    public LocalDateTime getScheduledEnd() { return scheduledEnd; }
    public void setScheduledEnd(LocalDateTime scheduledEnd) { this.scheduledEnd = scheduledEnd; }
    public LocalDateTime getActualStart() { return actualStart; }
    public void setActualStart(LocalDateTime actualStart) { this.actualStart = actualStart; }
    public LocalDateTime getActualEnd() { return actualEnd; }
    public void setActualEnd(LocalDateTime actualEnd) { this.actualEnd = actualEnd; }
    public Long getAssigneeId() { return assigneeId; }
    public void setAssigneeId(Long assigneeId) { this.assigneeId = assigneeId; }
    public String getAssigneeName() { return assigneeName; }
    public void setAssigneeName(String assigneeName) { this.assigneeName = assigneeName; }
    public String getTeamName() { return teamName; }
    public void setTeamName(String teamName) { this.teamName = teamName; }
    public Integer getTotalPoints() { return totalPoints; }
    public void setTotalPoints(Integer totalPoints) { this.totalPoints = totalPoints; }
    public Integer getCompletedPoints() { return completedPoints; }
    public void setCompletedPoints(Integer completedPoints) { this.completedPoints = completedPoints; }
    public Integer getMissedPoints() { return missedPoints; }
    public void setMissedPoints(Integer missedPoints) { this.missedPoints = missedPoints; }
    public Integer getAbnormalCount() { return abnormalCount; }
    public void setAbnormalCount(Integer abnormalCount) { this.abnormalCount = abnormalCount; }
    public Boolean getTimeoutWarned() { return timeoutWarned; }
    public void setTimeoutWarned(Boolean timeoutWarned) { this.timeoutWarned = timeoutWarned; }
    public String getRouteType() { return routeType; }
    public void setRouteType(String routeType) { this.routeType = routeType; }
    public Double getRouteDistance() { return routeDistance; }
    public void setRouteDistance(Double routeDistance) { this.routeDistance = routeDistance; }
    public Double getSequentialDistance() { return sequentialDistance; }
    public void setSequentialDistance(Double sequentialDistance) { this.sequentialDistance = sequentialDistance; }
    public Double getOptimizedDistance() { return optimizedDistance; }
    public void setOptimizedDistance(Double optimizedDistance) { this.optimizedDistance = optimizedDistance; }
    public Double getDistanceSaved() { return distanceSaved; }
    public void setDistanceSaved(Double distanceSaved) { this.distanceSaved = distanceSaved; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
