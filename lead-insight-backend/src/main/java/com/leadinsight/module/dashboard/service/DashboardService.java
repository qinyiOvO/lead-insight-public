package com.leadinsight.module.dashboard.service;

import com.leadinsight.module.dashboard.vo.DistributionVO;
import com.leadinsight.module.dashboard.vo.OverviewVO;
import com.leadinsight.module.dashboard.vo.TrendVO;

import java.util.List;

/**
 * 销售主管看板服务。不依赖任何 HTTP 对象，只接收/返回业务数据。
 */
public interface DashboardService {

    /** 总览：各状态计数、转化率、待人工数（R-3 四字段口径） */
    OverviewVO overview();

    /** 按线索状态分布（覆盖 LeadStatus 四值，零计数也返回） */
    List<DistributionVO> statusDistribution();

    /** 按意图类别分布（覆盖 IntentCategory 五值，零计数也返回） */
    List<DistributionVO> categoryDistribution();

    /** 按日趋势（新建数 + 成交数，连续 days 天含零值日，days 默认 30、上限 90） */
    List<TrendVO> trend(Integer days);
}
