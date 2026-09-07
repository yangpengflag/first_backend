package com.mooc.backend.exception;

import org.springframework.http.HttpStatus;

/**
 * 通知域业务异常（自治，不依赖 auth 包错误码表）。
 *
 * <p>由本模块的 {@code NotificationExceptionHandler} 翻译为统一错误信封
 * {@code {"request_id":...,"error":{"code":...,"message":...}}}。
 * <b>防枚举约定</b>：目标通知不存在与「非本人通知」共用 {@code NOTIFICATION_NOT_FOUND}
 * 一个错误码与同一个 404，不泄露他人通知 id 的存在性（spec：越权与状态门禁）。
 */
public class NotificationException extends RuntimeException {

    /** 目标通知不存在，或非当前用户的通知（同型 404，防枚举）。 */
    public static final String NOTIFICATION_NOT_FOUND = "NOTIFICATION_NOT_FOUND";

    /** 游标参数非法（解码失败 / 结构不符）。 */
    public static final String VALIDATION_FAILED = "VALIDATION_FAILED";

    private final HttpStatus status;
    private final String code;

    private NotificationException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    /** 他人通知或不存在 → 404（同型错误，不泄露存在性）。 */
    public static NotificationException notificationNotFound() {
        return new NotificationException(HttpStatus.NOT_FOUND, NOTIFICATION_NOT_FOUND, "Notification not found.");
    }

    /** 非法游标 → 400。 */
    public static NotificationException invalidCursor() {
        return new NotificationException(HttpStatus.BAD_REQUEST, VALIDATION_FAILED, "Invalid cursor.");
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
