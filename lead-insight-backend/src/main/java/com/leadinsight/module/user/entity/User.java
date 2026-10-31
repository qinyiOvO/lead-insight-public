package com.leadinsight.module.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 系统用户（表 sys_user）。字段与 schema-v1.1.sql 一一对应。
 *
 * <p>created_at / updated_at 由数据库默认值与 ON UPDATE 维护；实体字段为 null 时
 * MyBatis-Plus 默认策略（NOT_NULL）不会写入该列，因此不会覆盖数据库默认值。</p>
 */
@Data
@TableName("sys_user")
public class User {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 登录账号，唯一 */
    private String username;

    /** BCrypt 密码哈希（60 位） */
    private String password;

    /** 姓名 / 显示名 */
    private String realName;

    /** 角色：SPECIALIST / SUPERVISOR */
    private String role;

    /** 账号状态：1=启用, 0=禁用 */
    private Integer status;

    /** 最近一次登录时间 */
    private LocalDateTime lastLoginAt;

    /** 创建时间（数据库维护，只读回显） */
    private LocalDateTime createdAt;

    /** 更新时间（数据库维护，只读回显） */
    private LocalDateTime updatedAt;
}
