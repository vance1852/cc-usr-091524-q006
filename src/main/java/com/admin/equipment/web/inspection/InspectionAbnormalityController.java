package com.admin.equipment.web.inspection;

import com.admin.equipment.model.AppUser;
import com.admin.equipment.model.inspection.InspectionAbnormality;
import com.admin.equipment.repo.inspection.InspectionAbnormalityRepository;
import com.admin.equipment.service.attachment.AttachmentService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 巡检异常的证据附件：绑定/解绑受控附件，质量工程师查看闭环记录时
 * 可看到每份证据的完整性状态与来源（上传者、引用它的异常与工单）。
 */
@RestController
@RequestMapping("/api/inspection/abnormalities")
public class InspectionAbnormalityController {

    private final InspectionAbnormalityRepository abnormalityRepo;
    private final AttachmentService attachmentService;

    public InspectionAbnormalityController(InspectionAbnormalityRepository abnormalityRepo,
                                           AttachmentService attachmentService) {
        this.abnormalityRepo = abnormalityRepo;
        this.attachmentService = attachmentService;
    }

    public record BindEvidenceRequest(Long attachmentId) {}

    /** 异常的证据列表：每份证据带完整性状态与全部业务来源引用。 */
    @GetMapping("/{id}/attachments")
    public ResponseEntity<?> listEvidence(@PathVariable Long id) {
        InspectionAbnormality ab = abnormalityRepo.findById(id).orElse(null);
        if (ab == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "异常不存在"));
        }
        return ResponseEntity.ok(Map.of(
                "abnormalityId", ab.getId(),
                "status", ab.getStatus(),
                "closedLoop", Boolean.TRUE.equals(ab.getClosedLoop()),
                "workOrderId", ab.getWorkOrderId() != null ? ab.getWorkOrderId() : 0,
                "evidences", attachmentService.listEvidenceForAbnormality(id)
        ));
    }

    /** 绑定已完成上传的附件为异常证据（校验和/大小/类型核验通过才允许）。 */
    @PostMapping("/{id}/attachments")
    public ResponseEntity<?> bindEvidence(@PathVariable Long id, @RequestBody BindEvidenceRequest req,
                                          HttpServletRequest request) {
        if (req.attachmentId() == null) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", "附件ID必填"));
        }
        try {
            AppUser user = (AppUser) request.getAttribute("currentUser");
            String operator = user != null ? user.getDisplayName() : "";
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(attachmentService.bindToAbnormality(req.attachmentId(), id, operator));
        } catch (AttachmentService.NotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", e.getMessage()));
        } catch (AttachmentService.StateException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        }
    }

    /** 解除异常与附件的引用（不删除文件），幂等。 */
    @DeleteMapping("/{id}/attachments/{attachmentId}")
    public ResponseEntity<?> unbindEvidence(@PathVariable Long id, @PathVariable Long attachmentId) {
        if (!abnormalityRepo.existsById(id)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "异常不存在"));
        }
        attachmentService.unbindFromAbnormality(attachmentId, id);
        return ResponseEntity.noContent().build();
    }
}
