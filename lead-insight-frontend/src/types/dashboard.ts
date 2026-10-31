/**
 * 销售看板类型 —— 以 openapi 1.3.0（原 1.2.2）为唯一真源（T3-05/T3-06）。
 * 4 个接口均需销售主管角色（40300），与路由 meta.roles:['SUPERVISOR'] 对齐。
 */

/** GET /dashboard/overview：转化率 = wonLeads / (totalLeads - invalidLeads)，分母剔除无效 */
export interface OverviewVO {
  totalLeads: number;
  invalidLeads: number;
  wonLeads: number;
  newCount: number;
  followingCount: number;
  /** 待人工处理（extract_status 为 FAILED/DEGRADED） */
  pendingManualCount: number;
  conversionRate: number;
}

export interface DistributionVO {
  key: string;
  label: string;
  value: number;
}

export interface TrendVO {
  date: string;
  createdCount: number;
  wonCount: number;
}
