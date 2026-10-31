package com.leadinsight.module.followup.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.leadinsight.module.followup.entity.LeadFollowUp;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 跟进记录数据访问。只做数据存取。
 */
@Mapper
public interface LeadFollowUpMapper extends BaseMapper<LeadFollowUp> {

    /**
     * 每线索首次成交日计数（K-4 裁定：trend.wonCount 口径）。
     *
     * <p>WON 是终态、线索只可能成交一次，故按 per-lead 取 MIN(created_at)（首次 WON 迁移时间）
     * 归日计数——保证看板任意窗口的趋势累计与线索口径可互相复算；迁移计数法在重复迁移的
     * 种子/脏历史数据下失真，已废弃。JOIN lead 且 deleted_at IS NULL：已逻辑删除线索的
     * followup 记录不参与计数，否则趋势累计会大于 overview.wonLeads（overview 走 @TableLogic
     * 自动排除已删线索，两侧必须同口径）。</p>
     *
     * @param start 窗口起点（含）；首次成交早于该时点的线索不计入
     * @return 行结构 {d=yyyy-MM-dd, cnt=当日首次成交线索数}（别名为 d/cnt，规避列名驼峰化）
     */
    @Select("SELECT DATE_FORMAT(t.first_won, '%Y-%m-%d') AS d, COUNT(*) AS cnt "
            + "FROM (SELECT f.lead_id, MIN(f.created_at) AS first_won "
            + "      FROM lead_follow_up f "
            + "      JOIN `lead` l ON l.id = f.lead_id AND l.deleted_at IS NULL "
            + "      WHERE f.to_status = 'WON' "
            + "      GROUP BY f.lead_id) t "
            + "WHERE t.first_won >= #{start} "
            + "GROUP BY d")
    List<Map<String, Object>> selectFirstWonDailyCounts(@Param("start") LocalDateTime start);
}
