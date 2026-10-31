package com.leadinsight.module.user.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.leadinsight.common.exception.BizException;
import com.leadinsight.common.result.ResultCode;
import com.leadinsight.module.user.entity.User;
import com.leadinsight.module.user.mapper.UserMapper;
import com.leadinsight.module.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户服务实现。仅做数据查询与存在性校验，不含 HTTP 依赖。
 */
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private static final int STATUS_ENABLED = 1;

    private final UserMapper userMapper;

    @Override
    public User findByUsername(String username) {
        if (username == null || username.isBlank()) {
            return null;
        }
        return userMapper.selectOne(Wrappers.<User>lambdaQuery()
                .eq(User::getUsername, username.trim())
                .last("limit 1"));
    }

    @Override
    public Map<Long, String> realNameMapByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        List<User> users = userMapper.selectList(Wrappers.<User>lambdaQuery()
                .select(User::getId, User::getRealName)
                .in(User::getId, ids));
        Map<Long, String> nameById = new HashMap<>(Math.max(users.size(), 1));
        for (User user : users) {
            if (user.getId() != null) {
                nameById.put(user.getId(), user.getRealName());
            }
        }
        return nameById;
    }

    @Override
    public User getExisting(Long id) {
        if (id == null) {
            throw new BizException(ResultCode.NOT_FOUND, "用户不存在");
        }
        User user = userMapper.selectById(id);
        if (user == null) {
            throw new BizException(ResultCode.NOT_FOUND, "用户不存在");
        }
        return user;
    }

    @Override
    public boolean isEnabled(User user) {
        return user != null && user.getStatus() != null && user.getStatus() == STATUS_ENABLED;
    }

    @Override
    public void updateLastLoginAt(Long userId) {
        if (userId == null) {
            return;
        }
        User patch = new User();
        patch.setId(userId);
        patch.setLastLoginAt(LocalDateTime.now());
        userMapper.updateById(patch);
    }
}
