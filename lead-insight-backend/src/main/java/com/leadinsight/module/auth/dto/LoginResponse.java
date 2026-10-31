package com.leadinsight.module.auth.dto;

import com.leadinsight.module.user.vo.UserVO;
import lombok.Data;

/**
 * 登出参（对应 openapi LoginResponse）。
 * 字段名严格为 token / tokenType / expiresIn / user。
 */
@Data
public class LoginResponse {

    private String token;

    private String tokenType = "Bearer";

    /** 有效期（秒） */
    private long expiresIn;

    private UserVO user;
}
