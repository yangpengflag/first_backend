package com.mooc.backend.messaging.exception;

import org.springframework.http.HttpStatus;

/**
 * 私信域业务异常（自治，不依赖 auth 包错误码表）。
 *
 * <p>由本模块的 {@code MessagingExceptionHandler} 翻译为统一错误信封
 * {@code {"request_id":...,"error":{"code":...,"message":...}}}。
 * <b>防枚举约定</b>：会话不存在与「非成员访问」共用 {@code CONVERSATION_NOT_FOUND}
 * 一个错误码与同一个 404，不泄露他人会话 id 的存在性；对方 DELETED / LOCKED 共用
 * {@code USER_UNAVAILABLE} 同一错误码与文案，不区分锁定与注销，防状态探测。
 */
public class MessagingException extends RuntimeException {

    /** 以自己为 recipientId 发起会话。 */
    public static final String MESSAGE_SELF = "MESSAGE_SELF";

    /** recipientId 对应用户不存在。 */
    public static final String USER_NOT_FOUND = "USER_NOT_FOUND";

    /** 对方不可达（DELETED / LOCKED 统一同型，不区分具体状态）。 */
    public static final String USER_UNAVAILABLE = "USER_UNAVAILABLE";

    /** 会话不存在，或当前用户非会话成员（同型 404，防枚举）。 */
    public static final String CONVERSATION_NOT_FOUND = "CONVERSATION_NOT_FOUND";

    /** 请求参数校验失败（内容空白 / 超长 / 游标非法）。 */
    public static final String VALIDATION_FAILED = "VALIDATION_FAILED";

    /** 触发限流（对齐全站 ErrorCode.RATE_LIMITED 的码与文案）。 */
    public static final String RATE_LIMITED = "RATE_LIMITED";

    /** 未认证（控制器防御分支；正常路径由 Spring Security 过滤链拦截）。 */
    public static final String UNAUTHENTICATED = "UNAUTHENTICATED";

    private final HttpStatus status;
    private final String code;

    private MessagingException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    /** recipientId 为自己 → 422。 */
    public static MessagingException messageSelf() {
        return new MessagingException(HttpStatus.UNPROCESSABLE_ENTITY, MESSAGE_SELF, "You cannot message yourself");
    }

    /** recipientId 用户不存在 → 404。 */
    public static MessagingException userNotFound() {
        return new MessagingException(HttpStatus.NOT_FOUND, USER_NOT_FOUND, "User not found.");
    }

    /** 对方 DELETED / LOCKED → 422（同一错误码与文案，不泄露具体状态）。 */
    public static MessagingException userUnavailable() {
        return new MessagingException(HttpStatus.UNPROCESSABLE_ENTITY, USER_UNAVAILABLE,
                "This user is not available for messages");
    }

    /** 会话不存在或非成员 → 404（同型错误，不泄露存在性）。 */
    public static MessagingException conversationNotFound() {
        return new MessagingException(HttpStatus.NOT_FOUND, CONVERSATION_NOT_FOUND, "Conversation not found");
    }

    /** 消息内容为空（null 或 trim 后空）→ 400。 */
    public static MessagingException contentEmpty() {
        return new MessagingException(HttpStatus.BAD_REQUEST, VALIDATION_FAILED,
                "Message content must not be empty.");
    }

    /** 消息内容 trim 后超过 2000 字符 → 400。 */
    public static MessagingException contentTooLong() {
        return new MessagingException(HttpStatus.BAD_REQUEST, VALIDATION_FAILED,
                "Message content must not exceed 2000 characters.");
    }

    /** 游标参数非法（解码失败 / 结构不符）→ 400。 */
    public static MessagingException invalidCursor() {
        return new MessagingException(HttpStatus.BAD_REQUEST, VALIDATION_FAILED, "Invalid cursor.");
    }

    /** 发送频率超限 → 429（对齐全站格式）。 */
    public static MessagingException rateLimited() {
        return new MessagingException(HttpStatus.TOO_MANY_REQUESTS, RATE_LIMITED,
                "Too many requests. Please try again later.");
    }

    /** 未认证 → 401（对齐全站文案；仅在过滤链兜底缺失时防御性触发）。 */
    public static MessagingException unauthenticated() {
        return new MessagingException(HttpStatus.UNAUTHORIZED, UNAUTHENTICATED, "Authentication required.");
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
