package com.leadinsight.module.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 登录入参（对应 openapi LoginRequest）。
 */
@Data
public class LoginRequest {

    @NotBlank(message = "用户名不能为空")
    @Size(max = 50, message = "用户名长度不能超过 50 个字符")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 64, message = "密码长度需在 6-64 个字符之间")
    private String password;
}
