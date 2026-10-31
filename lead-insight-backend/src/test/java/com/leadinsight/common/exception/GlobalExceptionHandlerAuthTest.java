package com.leadinsight.common.exception;

import com.leadinsight.common.result.ResultCode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 方法级安全 403 出口锚定（走查行 8：@PreAuthorize 判否必须回 403/40300 统一 R 形态，
 * 绝不能落进兜底 Exception 分支变 50000/500）。
 *
 * <p>Spring Security 6.3+ 方法安全判否抛 AuthorizationDeniedException（AccessDeniedException
 * 子类）——GlobalExceptionHandler 以父类 @ExceptionHandler 兜住子类，此测试同时锚定
 * 父类与子类两条路径。</p>
 */
class GlobalExceptionHandlerAuthTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void accessDeniedException_mapsTo403Forbidden() {
        ResponseEntity<Object> response = invoke(new AccessDeniedException("denied"));
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertRShape(response, ResultCode.FORBIDDEN.getCode());
    }

    @Test
    void authorizationDeniedException_mapsTo403Forbidden() {
        // 方法级 @PreAuthorize 实际抛出的子类——必须同样命中 403 出口
        ResponseEntity<Object> response = invoke(new AuthorizationDeniedException("denied"));
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertRShape(response, ResultCode.FORBIDDEN.getCode());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ResponseEntity<Object> invoke(AccessDeniedException ex) {
        return (ResponseEntity) handler.handleAccessDenied(ex);
    }

    private void assertRShape(ResponseEntity<Object> response, int expectedCode) {
        Object body = response.getBody();
        assertNotNull(body, "403 响应体必须是统一 R 形态，不允许空体");
        com.leadinsight.common.result.R<?> r = (com.leadinsight.common.result.R<?>) body;
        assertEquals(expectedCode, r.getCode());
        assertEquals(ResultCode.FORBIDDEN.getMessage(), r.getMessage());
        assertNull(r.getData());
    }
}
