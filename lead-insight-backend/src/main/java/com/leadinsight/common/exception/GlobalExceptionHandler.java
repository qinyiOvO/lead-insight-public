package com.leadinsight.common.exception;

import com.leadinsight.common.result.R;
import com.leadinsight.common.result.ResultCode;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全局异常处理。
 * 第一层 参数校验 -> 40001；第二层 业务规则 -> 业务码；第三层 兜底 -> 50000。
 * 所有响应统一为 R{code,message,data}，绝不外泄堆栈细节。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 请求体字段校验失败：逐字段返回错误，满足 AC-08 的字段级提示 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<R<Map<String, String>>> handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            errors.putIfAbsent(fe.getField(), fe.getDefaultMessage());
        }
        return buildParamError(errors);
    }

    /** 表单/对象绑定校验失败 */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<R<Map<String, String>>> handleBind(BindException ex) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            errors.putIfAbsent(fe.getField(), fe.getDefaultMessage());
        }
        return buildParamError(errors);
    }

    /** 方法参数（@RequestParam/@PathVariable）约束失败 */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<R<Map<String, String>>> handleConstraintViolation(ConstraintViolationException ex) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (ConstraintViolation<?> cv : ex.getConstraintViolations()) {
            errors.putIfAbsent(String.valueOf(cv.getPropertyPath()), cv.getMessage());
        }
        return buildParamError(errors);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<R<Void>> handleNotReadable(HttpMessageNotReadableException ex) {
        log.warn("请求体不可解析: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(R.fail(ResultCode.PARAM_ERROR, "请求体格式错误或字段类型不匹配"));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<R<Void>> handleMissingParam(MissingServletRequestParameterException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(R.fail(ResultCode.PARAM_ERROR, "缺少必填参数: " + ex.getParameterName()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<R<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(R.fail(ResultCode.PARAM_ERROR, "参数类型错误: " + ex.getName()));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<R<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(R.fail(ResultCode.PARAM_ERROR, "不支持的请求方法: " + ex.getMethod()));
    }

    /** 请求 Content-Type 不受支持（如该传 application/json 却传了别的）→ 415 */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<R<Void>> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(R.fail(ResultCode.PARAM_ERROR, "不支持的请求内容类型: " + ex.getContentType()));
    }

    /** 客户端 Accept 无法被满足（如只接受 XML 而接口只产出 JSON）→ 406 */
    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<R<Void>> handleMediaTypeNotAcceptable(HttpMediaTypeNotAcceptableException ex) {
        return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE)
                .body(R.fail(ResultCode.PARAM_ERROR, "无法产出客户端可接受的响应类型"));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<R<Void>> handleNoResource(NoResourceFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(R.fail(ResultCode.NOT_FOUND, "接口或资源不存在"));
    }

    /** 认证失败（例如方法级 @PreAuthorize 抛出的认证异常） */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<R<Void>> handleAuthentication(AuthenticationException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(R.fail(ResultCode.UNAUTHORIZED, ResultCode.UNAUTHORIZED.getMessage()));
    }

    /** 授权失败：必须显式处理，否则会被兜底 Exception 分支吞成 50000 */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<R<Void>> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(R.fail(ResultCode.FORBIDDEN, ResultCode.FORBIDDEN.getMessage()));
    }

    /** 业务异常：使用其自带业务码与建议 HTTP 状态 */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<R<Object>> handleBiz(BizException ex) {
        ResultCode rc = ex.getResultCode();
        Object data = ex.getFieldErrors();
        log.info("业务异常 code={} message={}", rc.getCode(), ex.getMessage());
        return ResponseEntity.status(rc.getHttpStatus())
                .body(R.fail(rc.getCode(), ex.getMessage(), data));
    }

    /** 兜底：记录完整堆栈，对外只暴露 50000 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<R<Void>> handleUnknown(Exception ex) {
        log.error("系统内部错误", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(R.fail(ResultCode.INTERNAL_ERROR, ResultCode.INTERNAL_ERROR.getMessage()));
    }

    private ResponseEntity<R<Map<String, String>>> buildParamError(Map<String, String> errors) {
        String message = errors.isEmpty()
                ? ResultCode.PARAM_ERROR.getMessage()
                : errors.entrySet().stream()
                        .map(e -> e.getKey() + ": " + e.getValue())
                        .reduce((a, b) -> a + "; " + b)
                        .orElse(ResultCode.PARAM_ERROR.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(R.fail(ResultCode.PARAM_ERROR.getCode(), message, errors));
    }
}
