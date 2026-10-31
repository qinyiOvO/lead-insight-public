package com.leadinsight.config;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.leadinsight.module.user.entity.User;
import com.leadinsight.module.user.enums.UserRole;
import com.leadinsight.module.user.mapper.UserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 演示账号幂等初始化（ARCHITECTURE-v1.1 §8.2）。
 *
 * <p>幂等：仅在账号缺失时插入，已存在则跳过，<b>绝不覆盖既有密码</b>。
 * 因此本类的改动不会影响已有库里的账号，清理重建时才生效。</p>
 *
 * <p><b>口令为什么不在源码里（2026-10-31）：</b>原先三个账号的明文口令写在本类常量里，
 * 等于任何读仓库的人都能登录演示站；配合登录页明文展示，形同没有账号体系。
 * 现改为从配置读取：</p>
 * <pre>
 * app:
 *   seed:
 *     specialist-password: ${SEED_SPECIALIST_PASSWORD:}
 *     supervisor-password: ${SEED_SUPERVISOR_PASSWORD:}
 *     probe-password:      ${SEED_PROBE_PASSWORD:}
 * </pre>
 * <p>取值来源优先级：环境变量 → {@code application-local.yml}（已 gitignore）。
 * <b>某项未配置时该账号不创建</b>，只打一条 WARN 说明缺哪一项——宁可没有账号，
 * 也不在公开仓库里留一个"人人可登"的默认口令。真实口令写在《使用手册》里（不入库）。</p>
 */
@Slf4j
@Component
public class DataInitializer implements CommandLineRunner {

    private static final int STATUS_ENABLED = 1;

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final String specialistPassword;
    private final String supervisorPassword;
    private final String probePassword;

    public DataInitializer(UserMapper userMapper,
                           PasswordEncoder passwordEncoder,
                           @Value("${app.seed.specialist-password:}") String specialistPassword,
                           @Value("${app.seed.supervisor-password:}") String supervisorPassword,
                           @Value("${app.seed.probe-password:}") String probePassword) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.specialistPassword = specialistPassword;
        this.supervisorPassword = supervisorPassword;
        this.probePassword = probePassword;
    }

    private record SeedUser(String username, String rawPassword, String realName, UserRole role,
                            String configKey) {
    }

    @Override
    public void run(String... args) {
        List<SeedUser> seeds = List.of(
                new SeedUser("specialist01", specialistPassword, "王销售", UserRole.SPECIALIST,
                        "app.seed.specialist-password"),
                new SeedUser("supervisor01", supervisorPassword, "李主管", UserRole.SUPERVISOR,
                        "app.seed.supervisor-password"),
                // 一次性废弃账号：仅用于 AC-03 登录限流验证（故意连错 5 次触发 429）。
                // 限流键为 login:fail:{username}（按账号隔离），锁定它不会影响任何主账号。
                new SeedUser("probe01", probePassword, "限流探针", UserRole.SPECIALIST,
                        "app.seed.probe-password")
        );
        for (SeedUser seed : seeds) {
            initUser(seed);
        }
    }

    private void initUser(SeedUser seed) {
        User existing = userMapper.selectOne(Wrappers.<User>lambdaQuery()
                .eq(User::getUsername, seed.username())
                .last("limit 1"));
        if (existing != null) {
            log.info("演示账号已存在，跳过初始化: username={}", seed.username());
            return;
        }
        if (seed.rawPassword() == null || seed.rawPassword().isBlank()) {
            log.warn("演示账号 {} 未创建：缺少配置 {}（仓库不提供默认口令）。"
                            + "请在 application-local.yml 或环境变量中设置后重启，"
                            + "否则将无法登录演示。",
                    seed.username(), seed.configKey());
            return;
        }

        User user = new User();
        user.setUsername(seed.username());
        user.setPassword(passwordEncoder.encode(seed.rawPassword()));
        user.setRealName(seed.realName());
        user.setRole(seed.role().name());
        user.setStatus(STATUS_ENABLED);
        userMapper.insert(user);
        log.info("演示账号已创建: username={} role={}", seed.username(), seed.role().name());
    }
}
