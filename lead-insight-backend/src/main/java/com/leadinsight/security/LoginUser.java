package com.leadinsight.security;

import com.leadinsight.module.user.entity.User;
import com.leadinsight.module.user.enums.UserRole;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * 认证主体。承载 JWT 声明中的用户身份与角色。
 */
@Getter
public class LoginUser implements UserDetails {

    private final Long userId;
    private final String username;
    private final String password;
    private final String realName;
    private final String role;
    private final boolean enabled;

    public LoginUser(Long userId, String username, String password, String realName, String role, boolean enabled) {
        this.userId = userId;
        this.username = username;
        this.password = password;
        this.realName = realName;
        this.role = role;
        this.enabled = enabled;
    }

    public static LoginUser from(User user, boolean enabled) {
        return new LoginUser(user.getId(), user.getUsername(), user.getPassword(),
                user.getRealName(), user.getRole(), enabled);
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        UserRole parsed = UserRole.fromCode(role);
        if (parsed == null) {
            return List.of();
        }
        return List.of(new SimpleGrantedAuthority(parsed.authority()));
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }
}
