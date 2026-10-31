package com.leadinsight.module.lead.service.impl;

import com.leadinsight.common.util.TextUtils;
import com.leadinsight.module.lead.vo.LeadVO;
import com.leadinsight.module.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 线索负责人姓名回填（openapi LeadVO.ownerName，v1.2.1）。
 *
 * <p>语义等价于 {@code LEFT JOIN sys_user ON lead.owner_id = sys_user.id}：主表行全保留，
 * 关联缺失（未认领 / 用户已不存在）为 NULL。实现上用「按 ownerId 集合批量 IN 查询」而非改分页 SQL，
 * 好处是<b>完全不触碰 {@code selectPage} 的 SQL</b>，因而不会影响
 * {@code PaginationInnerInterceptor} 的 count 语句生成，也不会破坏
 * {@code @TableLogic} 自动追加的 {@code deleted_at IS NULL} 条件；整页仅多一次 IN 查询（非 N+1）。</p>
 */
@Component
@RequiredArgsConstructor
public class LeadOwnerEnricher {

    private final UserService userService;

    /**
     * 批量回填。未认领（ownerId 为 null）或用户查不到 / real_name 为空，一律保持 null，
     * 绝不写空串 ""（前端对 null→「未认领」与 ""→空白格的渲染不同）。
     */
    public void enrich(List<? extends LeadVO> vos) {
        if (vos == null || vos.isEmpty()) {
            return;
        }
        Set<Long> ownerIds = vos.stream()
                .map(LeadVO::getOwnerId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (ownerIds.isEmpty()) {
            return;
        }
        Map<Long, String> realNameById = userService.realNameMapByIds(ownerIds);
        for (LeadVO vo : vos) {
            Long ownerId = vo.getOwnerId();
            if (ownerId != null) {
                String realName = realNameById.get(ownerId);
                vo.setOwnerName(TextUtils.isBlank(realName) ? null : realName);
            }
        }
    }

    /** 单条便捷回填（创建 / 认领 / 状态迁移 / 编辑 / 详情等返回单对象的路径）。 */
    public void enrich(LeadVO vo) {
        if (vo != null) {
            enrich(List.of(vo));
        }
    }
}
