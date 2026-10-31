/** 销售看板接口 —— 路径以 openapi 1.3.0 为唯一真源（T3-06 前端侧；4 接口均 SUPERVISOR-only） */
import { http } from '@/api/http';
import type { DistributionVO, OverviewVO, TrendVO } from '@/types/dashboard';

/** GET /api/v1/dashboard/overview 总览（各状态计数、转化率、待人工数） */
export function getDashboardOverview(): Promise<OverviewVO> {
  return http.get<OverviewVO>('/api/v1/dashboard/overview');
}

/** GET /api/v1/dashboard/status-distribution 状态分布（donut） */
export function getStatusDistribution(): Promise<DistributionVO[]> {
  return http.get<DistributionVO[]>('/api/v1/dashboard/status-distribution');
}

/** GET /api/v1/dashboard/category-distribution 意图类别分布（横向条形，按 value 降序） */
export function getCategoryDistribution(): Promise<DistributionVO[]> {
  return http.get<DistributionVO[]>('/api/v1/dashboard/category-distribution');
}

/** GET /api/v1/dashboard/trend 时间趋势（days 默认 30；新增 vs 已成交双线） */
export function getDashboardTrend(days = 30): Promise<TrendVO[]> {
  return http.get<TrendVO[]>('/api/v1/dashboard/trend', { days });
}
