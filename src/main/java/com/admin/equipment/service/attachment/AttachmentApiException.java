package com.admin.equipment.service.attachment;

import org.springframework.http.HttpStatus;

/** 附件业务异常：携带明确的 HTTP 状态，由控制器统一转成 JSON 错误响应。 */
public class AttachmentApiException extends RuntimeException {

    private final HttpStatus status;

    public AttachmentApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public static AttachmentApiException notFound(String message) {
        return new AttachmentApiException(HttpStatus.NOT_FOUND, message);
    }

    public static AttachmentApiException conflict(String message) {
        return new AttachmentApiException(HttpStatus.CONFLICT, message);
    }

    public static AttachmentApiException unprocessable(String message) {
        return new AttachmentApiException(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }

    /** 元数据存在但物理文件丢失等完整性降级场景。 */
    public static AttachmentApiException gone(String message) {
        return new AttachmentApiException(HttpStatus.GONE, message);
    }
}
