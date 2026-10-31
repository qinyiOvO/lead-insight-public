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

    /** 登出（无状态 JWT：服务端不维护会话，R1 无黑名单） */
    void logout(Long userId);

    /** 获取当前登录用户信息 */
    UserVO getCurrentUser(Long userId);
}
