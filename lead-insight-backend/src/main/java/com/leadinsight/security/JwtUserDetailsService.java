package com.leadinsight.security;

import com.leadinsight.module.user.entity.User;
import com.leadinsight.module.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * UserDetails 装载。作为 UserDetailsService Bean 存在，
 * 同时避免 Spring Boot 自动生成默认内存用户与随机密码。
 */
@Service
@RequiredArgsConstructor
public class JwtUserDetailsService implements UserDetailsService {

    private final UserService userService;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = userService.findByUsername(username);
        if (user == null) {
            throw new UsernameNotFoundException("用户不存在");
        }
        return LoginUser.from(user, userService.isEnabled(user));
    }
}
