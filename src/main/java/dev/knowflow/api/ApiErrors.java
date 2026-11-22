package dev.knowflow.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import jakarta.servlet.http.HttpServletRequest;

@RestControllerAdvice
public class ApiErrors {
    private static final Logger log = LoggerFactory.getLogger(ApiErrors.class);
    public record ErrorBody(String code, String message, String requestId) {}
    @ExceptionHandler(ApiException.class)
    ResponseEntity<ErrorBody> api(ApiException ex, HttpServletRequest request) { return ResponseEntity.status(ex.status()).body(new ErrorBody(ex.code(), ex.getMessage(), RequestIds.get(request))); }
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class, org.springframework.web.multipart.support.MissingServletRequestPartException.class})
    ResponseEntity<ErrorBody> invalid(Exception ex, HttpServletRequest request) { return ResponseEntity.badRequest().body(new ErrorBody("INVALID_INPUT", "请求格式或字段不合法", RequestIds.get(request))); }
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ErrorBody> tooLarge(HttpServletRequest request) { return ResponseEntity.status(413).body(new ErrorBody("FILE_TOO_LARGE", "TXT 文件不能超过 2 MiB", RequestIds.get(request))); }
    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorBody> unexpected(Exception ex, HttpServletRequest request) {
        // Provider exception bodies can contain credentials or source text: do not log their messages.
        log.error("Request failed: {}", ex.getClass().getSimpleName());
        return ResponseEntity.internalServerError().body(new ErrorBody("INTERNAL_ERROR", "操作失败，请检查服务状态后重试", RequestIds.get(request)));
    }
}
