package com.admin.equipment.model.inspection;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "inspection_task_points")
public class InspectionTaskPoint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(name = "point_id", nullable = false)
    private Long pointId;

    @Column(name = "point_code", length = 32)
    private String pointCode = "";

    @Column(name = "point_name", length = 128)
    private String pointName = "";

    @Column(name = "point_location", length = 256)
    private String pointLocation = "";

    @Column(name = "planned_sequence")
    private Integer plannedSequence = 0;

    @Column(name = "actual_sequence")
    private Integer actualSequence;

    @Column(name = "coord_x")
    private Double coordX;

    @Column(name = "coord_y")
    private Double coordY;

    @Column(name = "equipment_ids", length = 512)
    private String equipmentIds = "";

    @Column(length = 16)
    private String status = "pending";

    @Column(name = "arrived_at")
    private LocalDateTime arrivedAt;

    @Column(name = "left_at")
    private LocalDateTime leftAt;

    @Column(name = "duration_seconds")
    private Long durationSeconds = 0L;

    @Column(name = "item_count")
    private Integer itemCount = 0;

    @Column(name = "qualified_count")
    private Integer qualifiedCount = 0;

    @Column(name = "abnormal_count")
    private Integer abnormalCount = 0;

    @Column(name = "is_missed")
    private Boolean isMissed = false;

    @Column(name = "inspector_name", length = 64)
    private String inspectorName = "";

    @Column(length = 512)
    private String remark = "";

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public Long getPointId() { return pointId; }
    public void setPointId(Long pointId) { this.pointId = pointId; }
    public String getPointCode() { return pointCode; }
    public void setPointCode(String pointCode) { this.pointCode = pointCode; }
    public String getPointName() { return pointName; }
    public void setPointName(String pointName) { this.pointName = pointName; }
    public String getPointLocation() { return pointLocation; }
    public void setPointLocation(String pointLocation) { this.pointLocation = pointLocation; }
    public Integer getPlannedSequence() { return plannedSequence; }
    public void setPlannedSequence(Integer plannedSequence) { this.plannedSequence = plannedSequence; }
    public Integer getActualSequence() { return actualSequence; }
    public void setActualSequence(Integer actualSequence) { this.actualSequence = actualSequence; }
    public Double getCoordX() { return coordX; }
    public void setCoordX(Double coordX) { this.coordX = coordX; }
    public Double getCoordY() { return coordY; }
    public void setCoordY(Double coordY) { this.coordY = coordY; }
    public String getEquipmentIds() { return equipmentIds; }
    public void setEquipmentIds(String equipmentIds) { this.equipmentIds = equipmentIds; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getArrivedAt() { return arrivedAt; }
    public void setArrivedAt(LocalDateTime arrivedAt) { this.arrivedAt = arrivedAt; }
    public LocalDateTime getLeftAt() { return leftAt; }
    public void setLeftAt(LocalDateTime leftAt) { this.leftAt = leftAt; }
    public Long getDurationSeconds() { return durationSeconds; }
    public void setDurationSeconds(Long durationSeconds) { this.durationSeconds = durationSeconds; }
    public Integer getItemCount() { return itemCount; }
    public void setItemCount(Integer itemCount) { this.itemCount = itemCount; }
    public Integer getQualifiedCount() { return qualifiedCount; }
    public void setQualifiedCount(Integer qualifiedCount) { this.qualifiedCount = qualifiedCount; }
    public Integer getAbnormalCount() { return abnormalCount; }
    public void setAbnormalCount(Integer abnormalCount) { this.abnormalCount = abnormalCount; }
    public Boolean getIsMissed() { return isMissed; }
    public void setIsMissed(Boolean isMissed) { this.isMissed = isMissed; }
    public String getInspectorName() { return inspectorName; }
    public void setInspectorName(String inspectorName) { this.inspectorName = inspectorName; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
