package com.leadinsight.module.dashboard.service.impl;

import com.leadinsight.module.dashboard.vo.OverviewVO;
import com.leadinsight.module.dashboard.vo.TrendVO;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 看板纯逻辑单测：R-3 转化率口径（OverviewVO.of）与趋势补零序列（buildSeries）。
 */
class DashboardServiceImplTest {

    // ---------------------- R-3 转化率口径 ----------------------

    @Test
    void overview_conversionRateExcludesInvalidFromDenominator() {
        // total=10, invalid=2, won=4 → 4/(10-2)=0.5000（无效线索剔除分母，R-3）
        OverviewVO vo = OverviewVO.of(10, 2, 4, 3, 1, 1);
        assertEquals(new BigDecimal("0.5000"), vo.getConversionRate());
        assertEquals(10L, vo.getTotalLeads());
        assertEquals(2L, vo.getInvalidLeads());
        assertEquals(4L, vo.getWonLeads());
    }

    @Test
    void overview_zeroDenominatorYieldsZeroRate() {
        // total=invalid（分母为 0）→ 返回 0，绝不抛除零异常
        OverviewVO vo = OverviewVO.of(5, 5, 0, 0, 0, 0);
        assertEquals(new BigDecimal("0.0000"), vo.getConversionRate());
    }

    @Test
    void overview_emptyBoardAllZero() {
        OverviewVO vo = OverviewVO.of(0, 0, 0, 0, 0, 0);
        assertEquals(0L, vo.getTotalLeads());
        assertEquals(new BigDecimal("0.0000"), vo.getConversionRate());
    }

    @Test
    void overview_rateRoundsHalfUpTo4Decimals() {
        // 1/3 = 0.3333（HALF_UP 截到 4 位）
        OverviewVO vo = OverviewVO.of(3, 0, 1, 0, 0, 0);
        assertEquals(new BigDecimal("0.3333"), vo.getConversionRate());
    }

    // ---------------------- 趋势补零序列 ----------------------

    @Test
    void buildSeries_fillsEveryDayWithZeroDefaults() {
        List<TrendVO> series = DashboardServiceImpl.buildSeries(7, Map.of(), Map.of());
        assertEquals(7, series.size());
        // 末位是今天，首位是 6 天前，连续递增
        assertEquals(LocalDate.now(), series.get(6).getDate());
        assertEquals(LocalDate.now().minusDays(6), series.get(0).getDate());
        for (TrendVO vo : series) {
            assertEquals(0L, vo.getCreatedCount());
            assertEquals(0L, vo.getWonCount());
        }
    }

    @Test
    void buildSeries_mapsCountsWithZeroFill() {
        LocalDate today = LocalDate.now();
        List<TrendVO> series = DashboardServiceImpl.buildSeries(3,
                Map.of(today, 5L), Map.of(today.minusDays(2), 2L));
        assertEquals(2L, series.get(0).getWonCount());   // 前天成交 2
        assertEquals(0L, series.get(0).getCreatedCount());
        assertEquals(5L, series.get(2).getCreatedCount()); // 今天新建 5
        assertEquals(0L, series.get(2).getWonCount());
    }

    @Test
    void buildSeries_oneDayWindowContainsOnlyToday() {
        List<TrendVO> series = DashboardServiceImpl.buildSeries(1, Map.of(), Map.of());
        assertEquals(1, series.size());
        assertEquals(LocalDate.now(), series.get(0).getDate());
    }

    // ---------------------- 天数窗口边界 ----------------------

    @Test
    void trendDays_defaultAndClampAreDocumented() {
        // 语义由 DashboardServiceImpl.trend 落实：null/<1 → 30，>90 → 90（此处锚定常量防漂移）
        assertEquals(30, DashboardServiceImpl.DEFAULT_DAYS);
        assertEquals(90, DashboardServiceImpl.MAX_DAYS);
        assertTrue(DashboardServiceImpl.MAX_DAYS >= DashboardServiceImpl.DEFAULT_DAYS);
    }

    // ---------------------- K-4 趋势成交口径 ----------------------

    @Test
    void buildSeries_k4PerLeadFirstWonMakesCumulativeEqualWonLeads() {
        // K-4：wonCount 输入已由 SQL 层按 per-lead MIN(created_at) 去重（每线索只计首次成交日），
        // 故窗口覆盖全部历史时趋势累计 == overview.wonLeads（答辩 QA 互相复算硬要求）。
        // 模拟 3 条线索全部今日首次成交：累计必须恰为 3，不因重复迁移而放大。
        List<TrendVO> series = DashboardServiceImpl.buildSeries(3, Map.of(), Map.of(LocalDate.now(), 3L));
        long cumulativeWon = series.stream().mapToLong(TrendVO::getWonCount).sum();
        assertEquals(3L, cumulativeWon,
                "K-4：趋势累计（全历史窗口）必须等于 wonLeads（此处 3 条线索首次成交均归今日）");
    }

    @Test
    void buildSeries_firstWonOutsideWindowIsNotCounted() {
        // 首次成交日在窗口之前的线索不计入本窗口任何一天（K-4 语义的自然结果）
        LocalDate longAgo = LocalDate.now().minusDays(60);
        List<TrendVO> series = DashboardServiceImpl.buildSeries(30, Map.of(), Map.of(longAgo, 2L));
        long cumulativeWon = series.stream().mapToLong(TrendVO::getWonCount).sum();
        assertEquals(0L, cumulativeWon, "窗口外首次成交不计入窗口内");
    }
}
