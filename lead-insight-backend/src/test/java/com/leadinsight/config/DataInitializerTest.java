package com.leadinsight.config;

import com.leadinsight.module.user.entity.User;
import com.leadinsight.module.user.mapper.UserMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 演示账号初始化的口令来源与幂等性（安全修复 M-11 的回归测试）。
 *
 * <p>守住的底线：<b>仓库里没有默认口令</b>——口令没配置时绝对不能凭空造出一个"人人可登"的账号；
 * 同时已存在的账号绝不能被覆盖（否则每次启动都会把用户改过的口令重置）。</p>
 */
class DataInitializerTest {

    private final UserMapper userMapper = mock(UserMapper.class);
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Test
    @DisplayName("三个口令都未配置：一个账号都不创建（仓库不提供默认口令）")
    void noAccountCreatedWhenPasswordsMissing() {
        when(userMapper.selectOne(anyWrapper())).thenReturn(null);

        new DataInitializer(userMapper, passwordEncoder, "", "  ", null).run();

        verify(userMapper, never()).insert(any(User.class));
    }

    @Test
    @DisplayName("只配了专员口令：只创建 specialist01，且库里存的是 BCrypt 哈希而非明文")
    void onlyConfiguredAccountsAreCreated() {
        when(userMapper.selectOne(anyWrapper())).thenReturn(null);

        new DataInitializer(userMapper, passwordEncoder, "Configured#Pass1", "", "").run();

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper, times(1)).insert(captor.capture());
        User created = captor.getValue();
        assertEquals("specialist01", created.getUsername());
        assertEquals("SPECIALIST", created.getRole());
        assertNotEquals("Configured#Pass1", created.getPassword(), "库里必须是哈希，不能是明文");
        assertTrue(passwordEncoder.matches("Configured#Pass1", created.getPassword()), "口令必须能校验通过");
    }

    @Test
    @DisplayName("账号已存在：即使配了口令也不覆盖（幂等，绝不重置用户已改的口令）")
    void existingAccountIsNeverOverwritten() {
        when(userMapper.selectOne(anyWrapper())).thenReturn(new User());

        new DataInitializer(userMapper, passwordEncoder, "A#123456", "B#123456", "C#123456").run();

        verify(userMapper, never()).insert(any(User.class));
    }

    /** 规避 BaseMapper 重载歧义：显式限定为 Wrapper 类型的匹配器 */
    @SuppressWarnings("unchecked")
    private static com.baomidou.mybatisplus.core.conditions.Wrapper<User> anyWrapper() {
        return (com.baomidou.mybatisplus.core.conditions.Wrapper<User>) any(
                com.baomidou.mybatisplus.core.conditions.Wrapper.class);
    }
}
