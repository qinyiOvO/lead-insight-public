package com.leadinsight.module.user.service;

import com.leadinsight.module.user.entity.User;

import java.util.Collection;
import java.util.Map;

/**
 * 用户服务。跨模块只暴露接口，禁止外部直连 UserMapper。
 */
public interface UserService {

    /** 按登录账号查询，不存在返回 null */
    User findByUsername(String username);

    /**
     * 批量查询用户姓名（id -&gt; real_name），供线索列表回填负责人，避免 N+1。
     * 入参为空返回空 Map；用户不存在则不出现在结果中（调用方按 null 处理）。
     */
    Map<Long, String> realNameMapByIds(Collection<Long> ids);

    /** 按主键查询，不存在抛 40400 */
    User getExisting(Long id);

    /** 校验账号是否启用（status=1） */
    boolean isEnabled(User user);

    /** 记录最近登录时间 */
    void updateLastLoginAt(Long userId);
}
