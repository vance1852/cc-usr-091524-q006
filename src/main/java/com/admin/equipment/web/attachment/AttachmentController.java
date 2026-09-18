package com.admin.equipment.web.attachment;

import com.admin.equipment.model.AppUser;
import com.admin.equipment.model.attachment.Attachment;
import com.admin.equipment.service.attachment.AttachmentProperties;
import com.admin.equipment.service.attachment.AttachmentService;
import com.admin.equipment.service.attachment.AttachmentStorageService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
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
 * 受控附件接口：分段上传（会话/分片/完成确认）、下载（JWT 鉴权 + 范围读取）、
 * 完整性校验、删除与过期清理。全部位于 /api/** 之下，由 AuthFilter 统一鉴权。
 */
@RestController
@RequestMapping("/api/attachments")
public class AttachmentController {

    private final AttachmentService service;
    private final AttachmentStorageService storage;
    private final AttachmentProperties props;

    public AttachmentController(AttachmentService service, AttachmentStorageService storage,
                                AttachmentProperties props) {
        this.service = service;
        this.storage = storage;
        this.props = props;
    }

    public record InitUploadRequest(String filename, Long size, String sha256, String mediaType) {}

    public record BindRequest(Long abnormalityId) {}

    // ------------------------------------------------------------------
    // 分段上传
    // ------------------------------------------------------------------

    /** 发起上传会话，返回 uploadId 与协商分片大小。 */
    @PostMapping("/uploads")
    public ResponseEntity<?> initUpload(@RequestBody InitUploadRequest req, HttpServletRequest request) {
        try {
            AppUser user = currentUser(request);
            Attachment a = service.createSession(req.filename(), req.size(), req.sha256(), req.mediaType(),
                    user != null ? user.getId() : null,
                    user != null ? user.getDisplayName() : "");
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                    "uploadId", a.getId(),
                    "status", a.getStatus(),
                    "chunkSizeBytes", props.getChunkSizeBytes(),
                    "expiresAt", a.getExpiresAt()
            ));
        } catch (AttachmentService.StateException e) {
            return unprocessable(e.getMessage());
        }
    }

    /** 会话状态：已上传分片序号列表，客户端据此断点续传。 */
    @GetMapping("/uploads/{id}")
    public ResponseEntity<?> sessionStatus(@PathVariable Long id) {
        try {
            AttachmentService.SessionView view = service.getSession(id);
            Attachment a = view.attachment();
            return ResponseEntity.ok(Map.of(
                    "uploadId", a.getId(),
                    "status", a.getStatus(),
                    "declaredSize", a.getSizeBytes(),
                    "sha256", a.getSha256(),
                    "chunkSizeBytes", view.chunkSizeBytes(),
                    "uploadedChunks", view.uploadedChunks(),
                    "expiresAt", a.getExpiresAt(),
                    "failReason", a.getFailReason() == null ? "" : a.getFailReason()
            ));
        } catch (AttachmentService.NotFoundException e) {
            return notFound(e.getMessage());
        }
    }

    /** 上传一个分片（application/octet-stream），同序号重复上传覆盖，幂等。 */
    @PutMapping("/uploads/{id}/chunks/{index}")
    public ResponseEntity<?> uploadChunk(@PathVariable Long id, @PathVariable int index,
                                         HttpServletRequest request) {
        try {
            long written = service.uploadChunk(id, index, request.getInputStream());
            return ResponseEntity.ok(Map.of(
                    "uploadId", id,
                    "index", index,
                    "receivedBytes", written,
                    "uploadedChunks", service.getSession(id).uploadedChunks()
            ));
        } catch (AttachmentService.NotFoundException e) {
            return notFound(e.getMessage());
        } catch (AttachmentService.StateException e) {
            return unprocessable(e.getMessage());
        } catch (IOException e) {
            return unprocessable("读取请求体失败: " + e.getMessage());
        }
    }

    /** 完成确认：核验 SHA-256 与声明大小，重复完成幂等。 */
    @PostMapping("/uploads/{id}/complete")
    public ResponseEntity<?> complete(@PathVariable Long id) {
        try {
            Attachment a = service.complete(id);
            return ResponseEntity.ok(a);
        } catch (AttachmentService.NotFoundException e) {
            return notFound(e.getMessage());
        } catch (AttachmentService.StateException e) {
            return unprocessable(e.getMessage());
        }
    }

    /** 手动触发过期/失败临时分片清理（另有定时任务自动执行）。 */
    @PostMapping("/uploads/cleanup")
    public ResponseEntity<?> cleanup() {
        AttachmentService.CleanupResult r = service.cleanupExpiredAndFailed();
        return ResponseEntity.ok(Map.of(
                "expiredSessions", r.expiredSessions(),
                "removedTempDirs", r.removedTempDirs()
        ));
    }

    // ------------------------------------------------------------------
    // 元数据 / 完整性 / 绑定
    // ------------------------------------------------------------------

    @GetMapping("/{id}")
    public ResponseEntity<?> metadata(@PathVariable Long id) {
        try {
            Attachment a = service.getRequired(id);
            return ResponseEntity.ok(Map.of(
                    "attachment", a,
                    "integrity", service.integrityOf(a)
            ));
        } catch (AttachmentService.NotFoundException e) {
            return notFound(e.getMessage());
        }
    }

    /** 重算文件 SHA-256 的完整完整性校验。 */
    @GetMapping("/{id}/verify")
    public ResponseEntity<?> verify(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(service.verifyIntegrity(id));
        } catch (AttachmentService.NotFoundException e) {
            return notFound(e.getMessage());
        }
    }

    /** 绑定附件到巡检异常（也可在异常接口下绑定，二者等价）。 */
    @PostMapping("/{id}/bind")
    public ResponseEntity<?> bind(@PathVariable Long id, @RequestBody BindRequest req, HttpServletRequest request) {
        if (req.abnormalityId() == null) {
            return unprocessable("异常ID必填");
        }
        try {
            AppUser user = currentUser(request);
            String operator = user != null ? user.getDisplayName() : "";
            return ResponseEntity.ok(service.bindToAbnormality(id, req.abnormalityId(), operator));
        } catch (AttachmentService.NotFoundException e) {
            return notFound(e.getMessage());
        } catch (AttachmentService.StateException e) {
            return unprocessable(e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 下载（范围读取）与删除
    // ------------------------------------------------------------------

    /**
     * 下载附件。路径由服务端存储键解析并校验，防止路径穿越；
     * 支持 Range 范围读取；数据库记录在但文件丢失时返回 410 降级响应。
     */
    @GetMapping("/{id}/download")
    public void download(@PathVariable Long id,
                         @RequestHeader(value = "Range", required = false) String rangeHeader,
                         HttpServletResponse response) throws IOException {
        Attachment a;
        try {
            a = service.getRequired(id);
        } catch (AttachmentService.NotFoundException e) {
            writeJson(response, HttpServletResponse.SC_NOT_FOUND, "附件不存在");
            return;
        }
        if (!Attachment.STATUS_COMPLETED.equals(a.getStatus())) {
            writeJson(response, 422, "附件未完成上传，当前状态: " + a.getStatus());
            return;
        }
        Path path;
        try {
            path = storage.resolveFinal(a.getStorageKey());
        } catch (IllegalArgumentException e) {
            writeJson(response, HttpServletResponse.SC_BAD_REQUEST, "非法的存储路径");
            return;
        }
        if (!Files.isRegularFile(path)) {
            // 数据库记录存在但文件丢失：降级响应，不暴露内部路径
            writeJson(response, HttpServletResponse.SC_GONE, "附件文件已丢失，请联系管理员恢复");
            return;
        }

        long fileSize = Files.size(path);
        long start = 0;
        long end = fileSize - 1;
        boolean partial = false;
        if (rangeHeader != null && !rangeHeader.isBlank()) {
            List<HttpRange> ranges;
            try {
                ranges = HttpRange.parseRanges(rangeHeader);
            } catch (IllegalArgumentException e) {
                response.setHeader("Content-Range", "bytes */" + fileSize);
                writeJson(response, 416, "范围请求不合法");
                return;
            }
            if (!ranges.isEmpty()) {
                HttpRange r = ranges.get(0);
                start = r.getRangeStart(fileSize);
                end = r.getRangeEnd(fileSize);
                if (start >= fileSize) {
                    response.setHeader("Content-Range", "bytes */" + fileSize);
                    writeJson(response, 416, "范围超出文件大小");
                    return;
                }
                partial = true;
            }
        }

        long length = end - start + 1;
        response.setStatus(partial ? HttpServletResponse.SC_PARTIAL_CONTENT : HttpServletResponse.SC_OK);
        response.setContentType(a.getMediaType());
        response.setHeader("Accept-Ranges", "bytes");
        response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''"
                + URLEncoder.encode(a.getOriginalName(), StandardCharsets.UTF_8).replace("+", "%20"));
        if (partial) {
            response.setHeader("Content-Range", "bytes " + start + "-" + end + "/" + fileSize);
        }
        response.setContentLengthLong(length);

        try (InputStream in = Files.newInputStream(path)) {
            skipFully(in, start);
            copyRange(in, response.getOutputStream(), length);
        }
    }

    /**
     * 删除未引用附件。存在业务引用（异常/工单证据）时返回 409 阻止删除。
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        try {
            service.deleteIfUnreferenced(id);
            return ResponseEntity.noContent().build();
        } catch (AttachmentService.NotFoundException e) {
            return notFound(e.getMessage());
        } catch (AttachmentService.ReferencedException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("detail", e.getMessage(), "references", e.getReferences()));
        }
    }

    // ------------------------------------------------------------------

    private static AppUser currentUser(HttpServletRequest request) {
        return (AppUser) request.getAttribute("currentUser");
    }

    private static void skipFully(InputStream in, long bytes) throws IOException {
        long remaining = bytes;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped <= 0) {
                if (in.read() == -1) throw new IOException("跳过范围超出文件末尾");
                skipped = 1;
            }
            remaining -= skipped;
        }
    }

    private static void copyRange(InputStream in, OutputStream out, long length) throws IOException {
        byte[] buf = new byte[8192];
        long remaining = length;
        while (remaining > 0) {
            int r = in.read(buf, 0, (int) Math.min(buf.length, remaining));
            if (r == -1) break;
            out.write(buf, 0, r);
            remaining -= r;
        }
        out.flush();
    }

    private static void writeJson(HttpServletResponse response, int status, String detail) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"detail\":\"" + detail + "\"}");
    }

    private static ResponseEntity<?> notFound(String msg) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", msg));
    }

    private static ResponseEntity<?> unprocessable(String msg) {
        return ResponseEntity.unprocessableEntity().body(Map.of("detail", msg));
    }
}
