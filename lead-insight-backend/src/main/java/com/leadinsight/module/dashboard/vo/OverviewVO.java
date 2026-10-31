package com.leadinsight.module.dashboard.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 看板总览出参（openapi 1.3.1 OverviewVO）。
 *
 * <p>R-3 裁定：必须同时返回 totalLeads / invalidLeads / wonLeads / conversionRate 四字段，
 * 转化率 = wonLeads / (totalLeads - invalidLeads)，QA 可用分子分母独立复算；
 * 无效线索不是销售机会，计入分母会系统性低估转化率，故剔除。</p>
 */
@Data
public class OverviewVO {

    /** 总线索数（不含逻辑删除） */
    private Long totalLeads;

    /** 无效线索数（status=INVALID） */
    private Long invalidLeads;

    /** 已成交线索数（status=WON） */
    private Long wonLeads;

    /**
     * 新线索数（status=NEW）。语义暂按"当前处于 NEW 状态的线索数"实现
     * （K-1/K-2 系列裁定未定月度口径，如需"本月新增"由后续裁定再改）。
     */
    private Long newCount;

    /** 跟进中线索数（status=FOLLOWING） */
    private Long followingCount;

    /** 待人工处理数（extract_status 为 FAILED/DEGRADED） */
    private Long pendingManualCount;

    /** 转化率 = wonLeads / (totalLeads - invalidLeads)，保留 4 位小数；分母为 0 时返回 0 */
    private BigDecimal conversionRate;

    public static OverviewVO of(long total, long invalid, long won, long newCount,
                                long following, long pendingManual) {
        OverviewVO vo = new OverviewVO();
        vo.setTotalLeads(total);
        vo.setInvalidLeads(invalid);
        vo.setWonLeads(won);
        vo.setNewCount(newCount);
        vo.setFollowingCount(following);
        vo.setPendingManualCount(pendingManual);
        long denominator = total - invalid;
        BigDecimal rate = denominator <= 0
                ? new BigDecimal("0.0000")
                : BigDecimal.valueOf(won).divide(BigDecimal.valueOf(denominator), 4, RoundingMode.HALF_UP);
        vo.setConversionRate(rate);
        return vo;
    }
}
