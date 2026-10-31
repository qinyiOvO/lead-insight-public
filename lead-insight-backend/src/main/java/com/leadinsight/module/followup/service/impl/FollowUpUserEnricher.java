package com.leadinsight.module.followup.service.impl;

import com.leadinsight.common.util.TextUtils;
import com.leadinsight.module.followup.vo.FollowUpVO;
import com.leadinsight.module.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 跟进记录人姓名回填（openapi FollowUpVO.userName，R1 走查 #3 追加）。
 *
 * <p>语义等价于 {@code LEFT JOIN sys_user ON lead_follow_up.user_id = sys_user.id}：主表行全保留，
 * 关联缺失（user_id 为 null / 用户已不存在 / real_name 为空）为 NULL。实现与线索域
 * {@code LeadOwnerEnricher} 同款——「按 userId 集合批量 IN 查询」而非改列表 SQL，
 * 整页仅多一次 IN 查询（非 N+1），不触碰 {@code @TableLogic} 追加条件。</p>
 */
@Component
@RequiredArgsConstructor
public class FollowUpUserEnricher {

    private final UserService userService;

    /**
     * 批量回填。user_id 为 null（系统自动留痕未关联）或用户查不到 / real_name 为空，一律保持 null，
     * 绝不写空串 ""（前端对 null→「—」与 ""→空白格的渲染不同）。
     */
    public void enrich(List<FollowUpVO> vos) {
        if (vos == null || vos.isEmpty()) {
            return;
        }
        Set<Long> userIds = vos.stream()
                .map(FollowUpVO::getUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (userIds.isEmpty()) {
            return;
        }
        Map<Long, String> realNameById = userService.realNameMapByIds(userIds);
        for (FollowUpVO vo : vos) {
            Long userId = vo.getUserId();
            if (userId != null) {
                String realName = realNameById.get(userId);
                vo.setUserName(TextUtils.isBlank(realName) ? null : realName);
            }
        }
    }

    /** 单条便捷回填（create 返回单对象的路径）。 */
    public void enrich(FollowUpVO vo) {
        if (vo != null) {
            enrich(List.of(vo));
        }
    }
}
