package com.leadinsight.module.dashboard.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.leadinsight.module.dashboard.vo.DistributionVO;
import com.leadinsight.module.dashboard.vo.OverviewVO;
import com.leadinsight.module.dashboard.vo.TrendVO;
import com.leadinsight.module.dashboard.service.DashboardService;
import com.leadinsight.module.followup.mapper.LeadFollowUpMapper;
import com.leadinsight.module.lead.entity.Lead;
import com.leadinsight.module.lead.enums.IntentCategory;
import com.leadinsight.module.lead.enums.LeadStatus;
import com.leadinsight.module.lead.mapper.LeadMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 销售主管看板服务实现（T3-05）。
 *
 * <p>口径：计数走 lead 表（@TableLogic 自动排除逻辑删除）；分布图固定覆盖全枚举值
 * （零计数也返回，前端图表 x 轴稳定）；成交时点按 K-4 裁定取每线索首次 WON 迁移日
 * （per-lead MIN(created_at) 归日），趋势累计与 overview.wonLeads 严格可复算。</p>
 */
@Service
@RequiredArgsConstructor
public class DashboardServiceImpl implements DashboardService {

    /** 趋势窗口默认与上限天数 */
    static final int DEFAULT_DAYS = 30;
    static final int MAX_DAYS = 90;

    private final LeadMapper leadMapper;
    private final LeadFollowUpMapper followUpMapper;

    @Override
    public OverviewVO overview() {
        // P-05 / §5.2-Q13：6 条 COUNT 合并为 1 条条件聚合（6 次扫描 → 1 次）
        Map<String, Object> row = leadMapper.selectOverviewCounts();
        return OverviewVO.of(
                countOf(row, "total"),
                countOf(row, "invalid_count"),
                countOf(row, "won_count"),
                countOf(row, "new_count"),
                countOf(row, "following_count"),
                countOf(row, "pending_manual"));
    }

    @Override
    public List<DistributionVO> statusDistribution() {
        Map<String, Long> counts = groupCount(leadMapper.selectMaps(
                Wrappers.<Lead>query().select("status as k", "count(*) as cnt").groupBy("k")));
        List<DistributionVO> list = new ArrayList<>();
        for (LeadStatus status : LeadStatus.values()) {
            list.add(new DistributionVO(status.name(), status.getLabel(),
                    counts.getOrDefault(status.name(), 0L)));
        }
        return list;
    }

    @Override
    public List<DistributionVO> categoryDistribution() {
        Map<String, Long> counts = groupCount(leadMapper.selectMaps(
                Wrappers.<Lead>query().select("intent_category as k", "count(*) as cnt").groupBy("k")));
        List<DistributionVO> list = new ArrayList<>();
        for (IntentCategory category : IntentCategory.values()) {
            list.add(new DistributionVO(category.name(), category.getLabel(),
                    counts.getOrDefault(category.name(), 0L)));
        }
        return list;
    }

    @Override
    public List<TrendVO> trend(Integer days) {
        int window = (days == null || days < 1) ? DEFAULT_DAYS : Math.min(days, MAX_DAYS);
        LocalDate today = LocalDate.now();
        LocalDate start = today.minusDays(window - 1L);

        Map<LocalDate, Long> created = toDailyCount(leadMapper.selectMaps(
                Wrappers.<Lead>query()
                        .select("DATE_FORMAT(created_at, '%Y-%m-%d') as d", "count(*) as cnt")
                        .ge("created_at", start.atStartOfDay())
                        // P-12c：补上界，避免脏数据/时钟回拨把未来数据计入，同时让范围走索引区间
                        .lt("created_at", today.plusDays(1).atStartOfDay())
                        .groupBy("d")));
        // 成交时点 = 每线索首次 WON 迁移日（K-4 裁定：per-lead MIN(created_at) 归日，
        // WON 是终态、线索只成交一次，趋势累计与 overview.wonLeads 可互相复算）
        Map<LocalDate, Long> won = toDailyCount(followUpMapper.selectFirstWonDailyCounts(start.atStartOfDay()));
        return buildSeries(window, created, won);
    }

    /** 连续 days 天补零序列（含今天倒推），纯函数可单测 */
    static List<TrendVO> buildSeries(int days, Map<LocalDate, Long> created, Map<LocalDate, Long> won) {
        LocalDate today = LocalDate.now();
        List<TrendVO> series = new ArrayList<>(days);
        for (int i = days - 1; i >= 0; i--) {
            LocalDate date = today.minusDays(i);
            series.add(TrendVO.of(date, created.getOrDefault(date, 0L), won.getOrDefault(date, 0L)));
        }
        return series;
    }

    /**
     * 聚合行取值：兼容 null / BigDecimal（{@code SUM}）/ Long（{@code COUNT}）。
     * 取不到或非数值一律按 0 处理——空表时看板必须显示 0 而不是空白。
     */
    private static long countOf(Map<String, Object> row, String column) {
        Object value = row == null ? null : row.get(column);
        return value instanceof Number n ? n.longValue() : 0L;
    }

    /** selectMaps 结果（别名为 k/cnt）→ key→计数（value 兼容 Long/BigInteger/Integer） */
    private Map<String, Long> groupCount(List<Map<String, Object>> rows) {
        Map<String, Long> counts = new HashMap<>();
        if (rows == null) {
            return counts;
        }
        for (Map<String, Object> row : rows) {
            Object key = row.get("k");
            Object value = row.get("cnt");
            if (key != null) {
                counts.put(key.toString(), value instanceof Number n ? n.longValue() : 0L);
            }
        }
        return counts;
    }

    /** selectMaps 结果（别名为 d/cnt）→ LocalDate 计数（无法解析的行丢弃） */
    private Map<LocalDate, Long> toDailyCount(List<Map<String, Object>> rows) {
        Map<LocalDate, Long> counts = new HashMap<>();
        if (rows == null) {
            return counts;
        }
        for (Map<String, Object> row : rows) {
            Object key = row.get("d");
            Object value = row.get("cnt");
            if (key == null) {
                continue;
            }
            try {
                counts.put(LocalDate.parse(key.toString()),
                        value instanceof Number n ? n.longValue() : 0L);
            } catch (Exception ignored) {
                // 非日期键（理论不可达），丢弃该行
            }
        }
        return counts;
    }
}
