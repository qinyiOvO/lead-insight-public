package com.leadinsight.module.auth.service.impl;

import com.leadinsight.cache.CacheService;
import com.leadinsight.common.exception.BizException;
import com.leadinsight.common.result.ResultCode;
import com.leadinsight.module.auth.dto.LoginRequest;
import com.leadinsight.module.auth.dto.LoginResponse;
import com.leadinsight.module.auth.service.AuthService;
import com.leadinsight.module.user.entity.User;
import com.leadinsight.module.user.service.UserService;
import com.leadinsight.module.user.vo.UserVO;
import com.leadinsight.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Locale;

/**
 * 认证服务实现。
 *
 * <p>AC-03：用户名不存在与密码错误必须返回完全相同的文案，避免账号枚举；
 * 同一账号在窗口期内失败达到阈值后按 42900 限流。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    /** 统一失败文案：不得区分「用户不存在」与「密码错误」（AC-03） */
    static final String LOGIN_FAILED_MESSAGE = "用户名或密码错误";

    private static final String FAIL_KEY_PREFIX = "login:fail:";

    private final UserService userService;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final CacheService cacheService;

    @Value("${security.login.max-failures:5}")
    private int maxFailures;

    @Value("${security.login.window-seconds:300}")
    private long windowSeconds;

    @Override
    public LoginResponse login(LoginRequest request) {
        String username = request.getUsername().trim();
        String failKey = FAIL_KEY_PREFIX + username.toLowerCase(Locale.ROOT);

        Long failures = cacheService.get(failKey, Long.class);
        if (failures != null && failures >= maxFailures) {
            log.info("登录被限流: username={} 窗口内失败次数={}", username, failures);
            throw new BizException(ResultCode.RATE_LIMITED,
                    "登录失败次数过多，请 " + (windowSeconds / 60) + " 分钟后再试");
        }

        User user = userService.findByUsername(username);
        boolean authenticated = user != null
                && userService.isEnabled(user)
                && passwordEncoder.matches(request.getPassword(), user.getPassword());

        if (!authenticated) {
            long count = cacheService.increment(failKey, Duration.ofSeconds(windowSeconds));
            log.info("登录失败: username={} 窗口内累计失败次数={}", username, count);
            throw new BizException(ResultCode.UNAUTHORIZED, LOGIN_FAILED_MESSAGE);
        }

        cacheService.delete(failKey);
        userService.updateLastLoginAt(user.getId());

        LoginResponse response = new LoginResponse();
        response.setToken(jwtUtil.generateToken(user.getId(), user.getUsername(), user.getRole()));
        response.setExpiresIn(jwtUtil.getExpireSeconds());
        response.setUser(UserVO.from(user));
        log.info("登录成功: username={} role={}", user.getUsername(), user.getRole());
        return response;
    }

    @Override
    public void logout(Long userId) {
        // 无状态 JWT 由前端清除令牌即可；服务端不保存会话，故无需副作用。
        log.info("用户登出: userId={}", userId);
    }

    @Override
    public UserVO getCurrentUser(Long userId) {
        return UserVO.from(userService.getExisting(userId));
    }
}
