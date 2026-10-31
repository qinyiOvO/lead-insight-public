package com.leadinsight.module.auth.service;

import com.leadinsight.module.auth.dto.LoginRequest;
import com.leadinsight.module.auth.dto.LoginResponse;
import com.leadinsight.module.user.vo.UserVO;

/**
 * 认证服务。不依赖任何 HTTP 对象。
 */
public interface AuthService {

    /** 账号密码登录，返回 JWT 与用户信息 */
    LoginResponse login(LoginRequest request);

    /**
     * 登出（DEF-07：服务端吊销）。
     *
     * @param token 本次请求携带的原始 JWT（Bearer 前缀已由 Controller 剥除）；登出即按剩余有效期
     *               列入进程内黑名单，之后同一令牌访问任何受保护端点均返回 40100。
     *               传 null/空白（无 Authorization 头）时仅记日志，不改变既有行为
     */
    void logout(Long userId, String token);

    /** 获取当前登录用户信息 */
    UserVO getCurrentUser(Long userId);
}
