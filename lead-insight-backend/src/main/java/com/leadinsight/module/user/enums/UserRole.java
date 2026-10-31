package com.leadinsight.module.user.enums;

import lombok.Getter;

/**
 * 系统角色。仅两类，与 sys_user.role 取值一致。
 */
@Getter
public enum UserRole {

    SPECIALIST("销售专员"),
    SUPERVISOR("销售主管");

    private final String label;

    UserRole(String label) {
        this.label = label;
    }

    /** Spring Security 权限名（ROLE_ 前缀，配合 hasRole 使用） */
    public String authority() {
        return "ROLE_" + name();
    }

    public static UserRole fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (UserRole role : values()) {
            if (role.name().equalsIgnoreCase(code.trim())) {
                return role;
            }
        }
        return null;
    }
}
