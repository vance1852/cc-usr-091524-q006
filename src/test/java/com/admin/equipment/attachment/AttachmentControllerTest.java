package com.admin.equipment.attachment;

import com.admin.equipment.model.AppUser;
import com.admin.equipment.model.attachment.EvidenceAttachment;
import com.admin.equipment.model.inspection.InspectionAbnormality;
import com.admin.equipment.repo.AppUserRepository;
import com.admin.equipment.repo.attachment.EvidenceAttachmentRepository;
import com.admin.equipment.repo.inspection.InspectionAbnormalityRepository;
import com.admin.equipment.security.JwtUtil;
import com.admin.equipment.service.attachment.AttachmentService;
import com.admin.equipment.service.attachment.dto.AttachmentView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 附件接口集成测试：JWT 鉴权、分片上传全链路、范围读取、
 * 文件丢失 410 降级、非法存储路径 410、未授权 401。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AttachmentControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private AppUserRepository userRepo;
    @Autowired private InspectionAbnormalityRepository abnormalityRepo;
    @Autowired private EvidenceAttachmentRepository attachmentRepo;
    @Autowired private AttachmentService attachmentService;
    @Autowired private com.admin.equipment.service.attachment.AttachmentStorage storage;

    private String token;

    @BeforeEach
    void setUp() {
        AppUser admin = userRepo.findByUsername("admin").orElseThrow();
        token = jwtUtil.createToken(admin.getId(), admin.getUsername());
    }

    private InspectionAbnormality abnormality() {
        InspectionAbnormality ab = new InspectionAbnormality();
        ab.setTaskId(9101L);
        ab.setTaskPointId(8101L);
        ab.setTitle("电机异响");
        ab.setDescription("声音较大");
        ab.setSeverity("high");
        ab.setStatus("reported");
        return abnormalityRepo.save(ab);
    }

    private byte[] data(int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) b[i] = (byte) (i * 7 + 3);
        return b;
    }

    private String sha(byte[] b) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    }

    /** 走 HTTP 接口完成一次 3 分片上传，返回异常 ID 与附件视图。 */
    private AttachmentView uploadOverHttp(Long abnormalityId, byte[] payload, String mediaType, String filename)
            throws Exception {
        int chunkSize = 1000;
        String body = "{\"abnormalityId\":" + abnormalityId
                + ",\"filename\":\"" + filename + "\""
                + ",\"mediaType\":\"" + mediaType + "\""
                + ",\"totalSize\":" + payload.length
                + ",\"chunkSize\":" + chunkSize
                + ",\"checksumSha256\":\"" + sha(payload) + "\"}";
        String resp = mockMvc.perform(post("/api/inspection/attachments/uploads")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String key = com.jayway.jsonpath.JsonPath.read(resp, "$.uploadKey");

        int total = (payload.length + chunkSize - 1) / chunkSize;
        for (int i = 0; i < total; i++) {
            int from = i * chunkSize;
            int to = Math.min(payload.length, from + chunkSize);
            byte[] part = new byte[to - from];
            System.arraycopy(payload, from, part, 0, part.length);
            mockMvc.perform(put("/api/inspection/attachments/uploads/{key}/chunks/{index}", key, i)
                            .header("Authorization", "Bearer " + token)
                            .content(part))
                    .andExpect(status().isOk());
        }
        String completeResp = mockMvc.perform(post(
                        "/api/inspection/attachments/uploads/{key}/complete", key)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Long id = ((Number) com.jayway.jsonpath.JsonPath.read(completeResp, "$.id")).longValue();
        return attachmentService.statusOf(id);
    }

    @Test
    void downloadWithoutToken_isRejected() throws Exception {
        mockMvc.perform(get("/api/inspection/attachments/1/download"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void fullChunkedFlowThenDownloadAndRange() throws Exception {
        InspectionAbnormality ab = abnormality();
        byte[] payload = data(2500);
        AttachmentView view = uploadOverHttp(ab.getId(), payload, "audio/mpeg", "电机异响.mp3");
        assertEquals_ok(view);

        // 闭环记录可见证据完整性状态与来源
        mockMvc.perform(get("/api/inspection/abnormalities/{id}/attachments", ab.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].integrityStatus").value("ok"))
                .andExpect(jsonPath("$[0].uploadedByName").value("平台管理员"))
                .andExpect(jsonPath("$[0].category").value("audio"));

        // 普通下载
        byte[] full = mockMvc.perform(get("/api/inspection/attachments/{id}/download", view.id())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"))
                .andExpect(header().string("X-Checksum-Sha256", sha(payload)))
                .andReturn().getResponse().getContentAsByteArray();
        org.junit.jupiter.api.Assertions.assertArrayEquals(payload, full);

        // 范围读取 bytes=100-199
        byte[] part = mockMvc.perform(get("/api/inspection/attachments/{id}/download", view.id())
                        .header("Authorization", "Bearer " + token)
                        .header(HttpHeaders.RANGE, "bytes=100-199"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 100-199/2500"))
                .andReturn().getResponse().getContentAsByteArray();
        org.junit.jupiter.api.Assertions.assertEquals(100, part.length);
        for (int i = 0; i < 100; i++) {
            org.junit.jupiter.api.Assertions.assertEquals(payload[100 + i], part[i]);
        }

        // 开区间 bytes=2400-
        byte[] tail = mockMvc.perform(get("/api/inspection/attachments/{id}/download", view.id())
                        .header("Authorization", "Bearer " + token)
                        .header(HttpHeaders.RANGE, "bytes=2400-"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 2400-2499/2500"))
                .andReturn().getResponse().getContentAsByteArray();
        org.junit.jupiter.api.Assertions.assertEquals(100, tail.length);

        // 不可满足的范围 → 416
        mockMvc.perform(get("/api/inspection/attachments/{id}/download", view.id())
                        .header("Authorization", "Bearer " + token)
                        .header(HttpHeaders.RANGE, "bytes=9999-"))
                .andExpect(status().isRequestedRangeNotSatisfiable());
    }

    @Test
    void resumeAfterGap_completesSuccessfully() throws Exception {
        InspectionAbnormality ab = abnormality();
        byte[] payload = data(1600);
        int chunkSize = 1000;
        String initBody = "{\"abnormalityId\":" + ab.getId()
                + ",\"filename\":\"铭牌.png\",\"mediaType\":\"image/png\",\"totalSize\":1600"
                + ",\"chunkSize\":1000,\"checksumSha256\":\"" + sha(payload) + "\"}";
        String resp = mockMvc.perform(post("/api/inspection/attachments/uploads")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json").content(initBody))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String key = com.jayway.jsonpath.JsonPath.read(resp, "$.uploadKey");

        byte[] part1 = new byte[1000];
        System.arraycopy(payload, 0, part1, 0, 1000);
        mockMvc.perform(put("/api/inspection/attachments/uploads/{key}/chunks/0", key)
                .header("Authorization", "Bearer " + token).content(part1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completed").value(false))
                .andExpect(jsonPath("$.missingChunks[0]").value(1));

        // 未传完直接完成 → 422
        mockMvc.perform(post("/api/inspection/attachments/uploads/{key}/complete", key)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnprocessableEntity());

        // 查询续传状态
        mockMvc.perform(get("/api/inspection/attachments/uploads/{key}", key)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receivedChunks[0]").value(0));

        byte[] part2 = new byte[600];
        System.arraycopy(payload, 1000, part2, 0, 600);
        mockMvc.perform(put("/api/inspection/attachments/uploads/{key}/chunks/1", key)
                        .header("Authorization", "Bearer " + token).content(part2))
                .andExpect(jsonPath("$.completed").value(true));

        mockMvc.perform(post("/api/inspection/attachments/uploads/{key}/complete", key)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.integrityStatus").value("ok"));
    }

    @Test
    void missingPhysicalFile_downloadReturns410() throws Exception {
        InspectionAbnormality ab = abnormality();
        AttachmentView view = uploadOverHttp(ab.getId(), data(500), "application/pdf", "报告.pdf");
        EvidenceAttachment row = attachmentRepo.findById(view.id()).orElseThrow();
        Files.deleteIfExists(storage.resolveSafely(row.getStoragePath()));

        mockMvc.perform(get("/api/inspection/attachments/{id}/download", view.id())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.integrityStatus").value("missing"));

        // 元数据与来源仍可查
        mockMvc.perform(get("/api/inspection/abnormalities/{id}/attachments", ab.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$[0].integrityStatus").value("missing"))
                .andExpect(jsonPath("$[0].contentAvailable").value(false));
    }

    @Test
    void illegalStoragePath_downloadReturns410_notFileLeak() throws Exception {
        InspectionAbnormality ab = abnormality();
        AttachmentView view = uploadOverHttp(ab.getId(), data(200), "text/plain", "note.txt");
        EvidenceAttachment row = attachmentRepo.findById(view.id()).orElseThrow();
        // 模拟元数据被篡改为穿越路径
        row.setStoragePath("../../../../../../etc/passwd");
        attachmentRepo.save(row);

        mockMvc.perform(get("/api/inspection/attachments/{id}/download", view.id())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isGone());
    }

    @Test
    void deleteReferencedEvidence_isForbidden_overHttp() throws Exception {
        InspectionAbnormality ab = abnormality();
        AttachmentView view = uploadOverHttp(ab.getId(), data(200), "image/png", "p.png");
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/inspection/attachments/{id}", view.id())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict());
    }

    private void assertEquals_ok(AttachmentView view) {
        org.junit.jupiter.api.Assertions.assertEquals("ok", view.integrityStatus());
    }
}
