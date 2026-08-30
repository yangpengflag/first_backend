package com.mooc.backend.auth.exception;

import com.mooc.backend.auth.api.ErrorResponse;
import com.mooc.backend.bookmarks.exception.BookmarkException;
import com.mooc.backend.comments.exception.CommentException;
import com.mooc.backend.posts.exception.PostException;
import com.mooc.backend.votes.exception.VoteException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;

/**
 * 全局异常处理器，把所有异常翻译为统一错误信封 {@code {"error":{...}}}。
 *
 * <p>注意：Spring Security 过滤器链（{@code JwtAuthFilter} / {@code UserStatusFilter} /
 * {@code RateLimitFilter}）抛出的异常发生在进入 {@code DispatcherServlet} 之前，
 * 不会流经本处理器，需由各过滤器自行写出错误响应。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(AuthException.class)
    public ResponseEntity<ErrorResponse> handleAuthException(AuthException ex) {
        return ResponseEntity.status(ex.getStatus())
                .body(ErrorResponse.of(ex.getErrorCode(), ex.getMessage(), ex.getDetails()));
    }

    @ExceptionHandler(PostException.class)
    public ResponseEntity<ErrorResponse> handlePostException(PostException ex) {
        return ResponseEntity.status(ex.getStatus())
                .body(ErrorResponse.of(ex.getErrorCode(), ex.getMessage(), ex.getDetails()));
    }

    @ExceptionHandler(CommentException.class)
    public ResponseEntity<ErrorResponse> handleCommentException(CommentException ex) {
        return ResponseEntity.status(ex.getStatus())
                .body(ErrorResponse.of(ex.getErrorCode(), ex.getMessage(), ex.getDetails()));
    }

    @ExceptionHandler(VoteException.class)
    public ResponseEntity<ErrorResponse> handleVoteException(VoteException ex) {
        return ResponseEntity.status(ex.getStatus())
                .body(ErrorResponse.of(ex.getErrorCode(), ex.getMessage(), ex.getDetails()));
    }

    @ExceptionHandler(BookmarkException.class)
    public ResponseEntity<ErrorResponse> handleBookmarkException(BookmarkException ex) {
        return ResponseEntity.status(ex.getStatus())
                .body(ErrorResponse.of(ex.getErrorCode(), ex.getMessage(), ex.getDetails()));
    }

    /** 参数校验失败：details 为逐字段的违规说明数组。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        List<String> details = ex.getBindingResult().getFieldErrors().stream()
                .map(this::describe)
                .sorted()
                .toList();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(ErrorCode.VALIDATION_FAILED,
                        ErrorCode.VALIDATION_FAILED.getDefaultMessage(), details));
    }

    /** 请求体不是合法 JSON，或类型无法绑定。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleNotReadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(ErrorCode.VALIDATION_FAILED, "Malformed request body."));
    }

    /**
     * 未匹配到任何静态资源。
     *
     * <p>Spring Framework 6.2 起 {@code ResourceHttpRequestHandler} 找不到资源时
     * <b>抛异常</b>而非直接写 404。若不显式处理，会落到下方兜底处理器变成 500——
     * 把「路径不存在」误报成服务端故障：既违背 HTTP 语义，也让外部探测行为
     * 污染错误日志（真正的 5xx 告警会被淹没）。
     *
     * <p>本处理器与本 change 直接相关：prod 下 springdoc 已禁用，{@code /v3/api-docs}
     * 与 {@code /swagger-ui/**} 无对应资源，正是走这条分支返回 <b>404</b>——
     * 而非 401（泄露路径存在）或 500（误报服务端故障）。
     *
     * <p>响应体为空：404 不返回业务错误信封，不泄露任何端点清单信息。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Void> handleNoResourceFound(NoResourceFoundException ex) {
        return ResponseEntity.notFound().build();
    }

    /**
     * 兜底处理器：只记录日志，绝不向客户端回显内部异常细节，避免泄露实现信息。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of(ErrorCode.INTERNAL_ERROR));
    }

    private String describe(FieldError error) {
        return error.getField() + ": " + error.getDefaultMessage();
    }
}
