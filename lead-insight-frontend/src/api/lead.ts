/** 线索接口 —— 路径以 openapi.yaml v1.2.2 为唯一真源（与路由名是两套命名空间） */
import { http, type RequestOptions } from '@/api/http';
import type { PageResult } from '@/types/common';
import type {
  FollowUpCreateRequest,
  FollowUpVO,
  LeadCreateRequest,
  LeadDetailVO,
  LeadQueryParams,
  LeadUpdateRequest,
  LeadVO,
  ScoreDetailVO,
  StatusChangeRequest,
} from '@/types/lead';

/** GET /api/v1/leads 分页查询（工作台与全量列表共用） */
export function listLeads(params: LeadQueryParams): Promise<PageResult<LeadVO>> {
  const query: Record<string, unknown> = {};
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') query[key] = value;
  });
  return http.get<PageResult<LeadVO>>('/api/v1/leads', query);
}

/** GET /api/v1/leads/{id} 详情（含抽取结果与评分明细） */
export function getLead(id: number): Promise<LeadDetailVO> {
  return http.get<LeadDetailVO>(`/api/v1/leads/${id}`);
}

/** POST /api/v1/leads 创建（R1 走手工录入模式：提交 customerName 等手工字段） */
export function createLead(payload: LeadCreateRequest): Promise<LeadVO> {
  return http.post<LeadVO>('/api/v1/leads', payload);
}

/** PATCH /api/v1/leads/{id} 编辑线索字段（人工修正） */
export function updateLead(id: number, payload: LeadUpdateRequest): Promise<LeadVO> {
  return http.patch<LeadVO>(`/api/v1/leads/${id}`, payload);
}

/** POST /api/v1/leads/{id}/extract 重新触发抽取（重试/人工修正后；模型不可用时返回 200 且 extractStatus=DEGRADED） */
export function reextractLead(id: number): Promise<LeadVO> {
  return http.post<LeadVO>(`/api/v1/leads/${id}/extract`);
}

/** DELETE /api/v1/leads/{id} 逻辑删除（调用前须二次确认，AC-10） */
export function deleteLead(id: number): Promise<unknown> {
  return http.delete<unknown>(`/api/v1/leads/${id}`);
}

/** POST /api/v1/leads/{id}/claim 认领（NEW -> FOLLOWING，绑定当前用户） */
export function claimLead(id: number): Promise<LeadVO> {
  return http.post<LeadVO>(`/api/v1/leads/${id}/claim`);
}

/** POST /api/v1/leads/{id}/status 状态迁移（受状态机约束，非法迁移返回 42201） */
export function changeLeadStatus(
  id: number,
  payload: StatusChangeRequest,
  options?: RequestOptions,
): Promise<LeadVO> {
  return http.post<LeadVO>(`/api/v1/leads/${id}/status`, payload, options);
}

/** GET /api/v1/leads/{id}/scores 评分明细 */
export function getLeadScores(id: number): Promise<ScoreDetailVO[]> {
  return http.get<ScoreDetailVO[]>(`/api/v1/leads/${id}/scores`);
}

/** GET /api/v1/leads/{id}/follow-ups 跟进记录列表 */
export function listFollowUps(id: number): Promise<FollowUpVO[]> {
  return http.get<FollowUpVO[]>(`/api/v1/leads/${id}/follow-ups`);
}

/** POST /api/v1/leads/{id}/follow-ups 新增跟进记录 */
export function createFollowUp(id: number, payload: FollowUpCreateRequest): Promise<FollowUpVO> {
  return http.post<FollowUpVO>(`/api/v1/leads/${id}/follow-ups`, payload);
}
