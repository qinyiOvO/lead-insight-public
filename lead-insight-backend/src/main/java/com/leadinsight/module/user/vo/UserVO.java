package com.leadinsight.module.user.vo;

import com.leadinsight.module.user.entity.User;
import lombok.Data;

/**
 * 用户出参（对应 openapi UserVO）。绝不包含密码字段。
 */
@Data
public class UserVO {

    private Long id;
    private String username;
    private String realName;
    private String role;

    public static UserVO from(User user) {
        UserVO vo = new UserVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setRealName(user.getRealName());
        vo.setRole(user.getRole());
        return vo;
    }
}
