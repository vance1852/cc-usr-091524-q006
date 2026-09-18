package com.admin.equipment.web.attachment;

import com.admin.equipment.model.AppUser;
import com.admin.equipment.service.attachment.AttachmentApiException;
import com.admin.equipment.service.attachment.AttachmentService;
import com.admin.equipment.service.attachment.dto.AttachmentView;
import com.admin.equipment.service.attachment.dto.ChunkReceiptResult;
import com.admin.equipment.service.attachment.dto.UploadInitiateResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 受控附件接口：所有接口均在 /api/** 之下，由 AuthFilter 强制 JWT 鉴权。
 * 分片上传流程：initiate → PUT chunks（可断点续传）→ complete（校验通过才绑定异常）。
 */
@RestController
@RequestMapping("/api/inspection")
public class AttachmentController {

    private final AttachmentService service;

    public AttachmentController(AttachmentService service) {
        this.service = service;
    }

    public record InitiateRequest(Long abnormalityId, String filename, String mediaType,
                                  Long totalSize, Long chunkSize, String checksumSha256) {}

    @ExceptionHandler(AttachmentApiException.class)
    public ResponseEntity<Map<String, String>> handleApi(AttachmentApiException e) {
        return ResponseEntity.status(e.getStatus()).body(Map.of("detail", e.getMessage()));
    }

    // ---------------- 分片上传 ----------------

    @PostMapping("/attachments/uploads")
    public ResponseEntity<UploadInitiateResult> initiate(@RequestBody InitiateRequest req,
                                                         HttpServletRequest request) {
        UploadInitiateResult r = service.initiate(req.abnormalityId(), req.filename(),
                req.mediaType(), req.totalSize() == null ? -1 : req.totalSize(),
                req.chunkSize(), req.checksumSha256(), currentUser(request));
        return ResponseEntity.status(HttpStatus.CREATED).body(r);
    }

    @PutMapping("/attachments/uploads/{key}/chunks/{index}")
    public ChunkReceiptResult uploadChunk(@PathVariable String key,
                                          @PathVariable int index,
                                          HttpServletRequest request) throws IOException {
        try (InputStream in = request.getInputStream()) {
            return service.uploadChunk(key, index, in);
        }
    }

    @GetMapping("/attachments/uploads/{key}")
    public UploadInitiateResult uploadStatus(@PathVariable String key) {
        return service.getUploadStatus(key);
    }

    @PostMapping("/attachments/uploads/{key}/complete")
    public AttachmentView complete(@PathVariable String key) {
        return service.complete(key);
    }

    @PostMapping("/attachments/uploads/{key}/abort")
    public ResponseEntity<Map<String, String>> abort(@PathVariable String key) {
        service.abort(key);
        return ResponseEntity.ok(Map.of("result", "upload aborted"));
    }

    // ---------------- 证据查询与完整性 ----------------

    @GetMapping("/abnormalities/{abnormalityId}/attachments")
    public List<AttachmentView> listByAbnormality(@PathVariable Long abnormalityId,
                                                  @RequestParam(defaultValue = "false") boolean verify) {
        return service.listByAbnormality(abnormalityId, verify);
    }

    @GetMapping("/attachments/work-order/{workOrderId}")
    public List<AttachmentView> listByWorkOrder(@PathVariable Long workOrderId) {
        return service.listByWorkOrder(workOrderId);
    }

    @GetMapping("/attachments/{id}")
    public AttachmentView getOne(@PathVariable Long id) {
        // 默认只做存在性/大小探测，避免每次列表级查询都重算哈希
        return service.statusOf(id);
    }

    @PostMapping("/attachments/{id}/verify")
    public AttachmentView verify(@PathVariable Long id) {
        return service.verify(id);
    }

    // ---------------- 下载（范围读取） ----------------

    @GetMapping("/attachments/{id}/download")
    public void download(@PathVariable Long id,
                         @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader,
                         HttpServletResponse response) throws IOException {
        AttachmentService.DownloadTarget target = service.openDownload(id);
        if (!target.contentAvailable()) {
            // 数据库记录存在但物理文件丢失：明确的降级响应而非 500
            response.setStatus(HttpStatus.GONE.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write("{\"detail\":\"证据文件已丢失，仅可查看元数据\","
                    + "\"integrityStatus\":\"missing\",\"attachmentId\":" + id + "}");
            return;
        }

        Path file = target.path();
        long total = Files.size(file);
        String mediaType = target.attachment().getMediaType();
        String filename = target.attachment().getOriginalFilename();

        response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
        response.setHeader("Content-Disposition", contentDisposition(filename));
        response.setHeader("X-Checksum-Sha256", target.attachment().getChecksumSha256());

        long[] range = parseRange(rangeHeader, total);
        if (rangeHeader != null && range == null) {
            response.setStatus(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE.value());
            response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes */" + total);
            return;
        }

        if (range == null) {
            response.setStatus(HttpStatus.OK.value());
            response.setContentType(mediaType);
            response.setContentLengthLong(total);
            writeRange(file, 0, total - 1, response);
        } else {
            long start = range[0];
            long end = range[1];
            long length = end - start + 1;
            response.setStatus(HttpStatus.PARTIAL_CONTENT.value());
            response.setContentType(mediaType);
            response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + total);
            response.setContentLengthLong(length);
            writeRange(file, start, end, response);
        }
    }

    // ---------------- 删除与维护 ----------------

    @DeleteMapping("/attachments/{id}")
    public ResponseEntity<Map<String, String>> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.ok(Map.of("result", "deleted"));
    }

    @PostMapping("/attachments/gc")
    public Map<String, Object> garbageCollect() {
        AttachmentService.CleanupResult r = service.garbageCollect();
        return Map.of("expiredSessions", r.expiredSessions(),
                "orphanTempDirs", r.orphanTempDirs(),
                "orphanEvidenceFiles", r.orphanEvidenceFiles());
    }

    @PostMapping("/attachments/cleanup-temp")
    public Map<String, Object> cleanupTemp() {
        AttachmentService.CleanupResult r = service.cleanupExpiredSessions();
        return Map.of("expiredSessions", r.expiredSessions(),
                "orphanTempDirs", r.orphanTempDirs());
    }

    // ---------------- 辅助 ----------------

    private void writeRange(Path file, long start, long end, HttpServletResponse response) throws IOException {
        try (InputStream in = Files.newInputStream(file);
             OutputStream out = response.getOutputStream()) {
            long skipped = 0;
            while (skipped < start) {
                long s = in.skip(start - skipped);
                if (s <= 0) break;
                skipped += s;
            }
            byte[] buf = new byte[64 * 1024];
            long remaining = end - start + 1;
            while (remaining > 0) {
                int toRead = (int) Math.min(buf.length, remaining);
                int read = in.read(buf, 0, toRead);
                if (read < 0) break;
                out.write(buf, 0, read);
                remaining -= read;
            }
            out.flush();
        }
    }

    /** 解析 bytes=start-end / bytes=start- / bytes=-suffix，非法或不可满足返回 null。 */
    private long[] parseRange(String header, long total) {
        if (header == null || header.isBlank()) return null;
        String prefix = "bytes=";
        if (!header.startsWith(prefix)) return null;
        String spec = header.substring(prefix.length()).trim();
        if (spec.contains(",")) {
            // 仅支持单区间，多区间请求按完整内容返回也是允许的行为
            return null;
        }
        int dash = spec.indexOf('-');
        if (dash < 0) return null;
        String startStr = spec.substring(0, dash).trim();
        String endStr = spec.substring(dash + 1).trim();
        try {
            long start;
            long end;
            if (startStr.isEmpty()) {
                long suffix = Long.parseLong(endStr);
                if (suffix <= 0) return null;
                start = Math.max(0, total - suffix);
                end = total - 1;
            } else {
                start = Long.parseLong(startStr);
                end = endStr.isEmpty() ? total - 1 : Long.parseLong(endStr);
            }
            if (start < 0 || start >= total || end < start) return null;
            if (end >= total) end = total - 1;
            return new long[]{start, end};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String contentDisposition(String filename) {
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        // 同时给出 ASCII 回退名与 RFC 5987 编码名，兼容中文文件名
        return "attachment; filename=\"evidence\";"
                + "filename*=UTF-8''" + encoded;
    }

    private AppUser currentUser(HttpServletRequest request) {
        return (AppUser) request.getAttribute("currentUser");
    }
}
