package com.mooc.backend.auth.exception;

import org.springframework.http.HttpStatus;

/**
 * 认证域统一业务异常。
 *
 * <p>携带 {@link ErrorCode}，由 {@code GlobalExceptionHandler} 翻译为统一错误信封与对应 HTTP 状态码，
 * 避免在 Controller 中散落状态码判断。
 */
public class AuthException extends RuntimeException {

    private final ErrorCode errorCode;
    private final HttpStatus status;
    private final Object details;

    public AuthException(ErrorCode errorCode) {
        this(errorCode, errorCode.getDefaultMessage(), null);
    }

    public AuthException(ErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    public AuthException(ErrorCode errorCode, String message, Object details) {
        super(message);
        this.errorCode = errorCode;
        this.status = errorCode.getStatus();
        this.details = details;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public Object getDetails() {
        return details;
    }

    public static AuthException invalidCredentials() {
        return new AuthException(ErrorCode.INVALID_CREDENTIALS);
    }

    public static AuthException accountLocked() {
        return new AuthException(ErrorCode.ACCOUNT_LOCKED);
    }

    public static AuthException accountDeleted() {
        return new AuthException(ErrorCode.ACCOUNT_DELETED);
    }

    public static AuthException emailNotVerified() {
        return new AuthException(ErrorCode.EMAIL_NOT_VERIFIED);
    }

    public static AuthException unauthenticated() {
        return new AuthException(ErrorCode.UNAUTHENTICATED);
    }

    public static AuthException invalidVerificationCode() {
        return new AuthException(ErrorCode.INVALID_VERIFICATION_CODE);
    }

    public static AuthException emailAlreadyRegistered() {
        return new AuthException(ErrorCode.EMAIL_ALREADY_REGISTERED);
    }

    public static AuthException invalidResetCode() {
        return new AuthException(ErrorCode.INVALID_RESET_CODE);
    }

    public static AuthException tokenInvalidated() {
        return new AuthException(ErrorCode.TOKEN_INVALIDATED);
    }

    public static AuthException rateLimited() {
        return new AuthException(ErrorCode.RATE_LIMITED);
    }

    public static AuthException validationFailed(Object details) {
        return new AuthException(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.getDefaultMessage(), details);
    }
}
