package com.leadinsight.module.dashboard.controller;

import com.leadinsight.common.result.R;
import com.leadinsight.module.dashboard.service.DashboardService;
import com.leadinsight.module.dashboard.vo.DistributionVO;
import com.leadinsight.module.dashboard.vo.OverviewVO;
import com.leadinsight.module.dashboard.vo.TrendVO;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 销售主管看板接口（openapi 1.3.1，T3-05）。Controller 只做参数透传、调用 Service、组装响应。
 * 角色鉴权按架构 §7：仅 SUPERVISOR（专员访问返回 40300）。
 */
@RestController
@RequestMapping("/api/v1/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    @GetMapping("/overview")
    @PreAuthorize("hasRole('SUPERVISOR')")
    public R<OverviewVO> overview() {
        return R.ok(dashboardService.overview());
    }

    @GetMapping("/status-distribution")
    @PreAuthorize("hasRole('SUPERVISOR')")
    public R<List<DistributionVO>> statusDistribution() {
        return R.ok(dashboardService.statusDistribution());
    }

    @GetMapping("/category-distribution")
    @PreAuthorize("hasRole('SUPERVISOR')")
    public R<List<DistributionVO>> categoryDistribution() {
        return R.ok(dashboardService.categoryDistribution());
    }

    @GetMapping("/trend")
    @PreAuthorize("hasRole('SUPERVISOR')")
    public R<List<TrendVO>> trend(@RequestParam(required = false) Integer days) {
        return R.ok(dashboardService.trend(days));
    }
}
