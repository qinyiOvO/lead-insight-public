package com.leadinsight.module.auth.controller;

import com.leadinsight.common.result.R;
import com.leadinsight.module.auth.dto.LoginRequest;
import com.leadinsight.module.auth.dto.LoginResponse;
import com.leadinsight.module.auth.service.AuthService;
import com.leadinsight.module.user.vo.UserVO;
import com.leadinsight.security.LoginUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口。Controller 只做参数校验、调用 Service、组装响应。
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public R<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return R.ok(authService.login(request));
    }

    @PostMapping("/logout")
    public R<Void> logout(@AuthenticationPrincipal LoginUser loginUser,
                          @RequestHeader(value = "Authorization", required = false) String authorization) {
        // DEF-07：服务端吊销需要原始令牌；Bearer 前缀在此剥除（HTTP 层关注点，不进 Service）
        authService.logout(loginUser.getUserId(), resolveBearerToken(authorization));
        return R.ok();
    }

    /** 剥除 "Bearer " 前缀；无头或格式不符时返回 null（Service 按无令牌处理，仅记日志） */
    private String resolveBearerToken(String authorization) {
        if (authorization != null && authorization.startsWith("Bearer ")) {
            String token = authorization.substring("Bearer ".length()).trim();
            return token.isEmpty() ? null : token;
        }
        return null;
    }

    @GetMapping("/me")
    public R<UserVO> me(@AuthenticationPrincipal LoginUser loginUser) {
        return R.ok(authService.getCurrentUser(loginUser.getUserId()));
    }
}
