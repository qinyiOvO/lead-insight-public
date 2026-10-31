package com.leadinsight.security;

import com.leadinsight.common.result.R;
import com.leadinsight.common.result.ResultCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 未认证入口：返回 JSON（{code:40100}）而不是 Spring 默认的 HTML 页面。
 * 过滤器解析失败时会写入 {@link JwtAuthenticationFilter#ATTR_AUTH_ERROR}，
 * 此处据此区分「令牌已过期(40101)」与「未认证/令牌无效(40100)」。
 */
@Component
@RequiredArgsConstructor
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        ResultCode code = ResultCode.UNAUTHORIZED;
        Object attr = request.getAttribute(JwtAuthenticationFilter.ATTR_AUTH_ERROR);
        if (attr instanceof ResultCode rc) {
            code = rc;
        }
        writeJson(response, code);
    }

    private void writeJson(HttpServletResponse response, ResultCode code) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(R.fail(code)));
    }
}
