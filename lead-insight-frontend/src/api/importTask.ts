/** 批量导入任务接口 —— 路径以 openapi 1.3.0 为唯一真源（T3-03/T3-04 前端侧） */
import { http } from '@/api/http';
import type { PageResult } from '@/types/common';
import type { LeadVO } from '@/types/lead';
import type {
  BatchImportRequest,
  ImportTaskItemVO,
  ImportTaskProgressVO,
  ImportTaskVO,
} from '@/types/import-task';

/** POST /api/v1/import-tasks 创建批量导入异步任务（202，异步处理中） */
export function createBatchImport(payload: BatchImportRequest): Promise<ImportTaskVO> {
  return http.post<ImportTaskVO>('/api/v1/import-tasks', payload);
}

/** GET /api/v1/import-tasks 任务列表（「近期导入任务」区块取最近 5 条） */
export function listImportTasks(params: { page?: number; limit?: number }): Promise<PageResult<ImportTaskVO>> {
  return http.get<PageResult<ImportTaskVO>>('/api/v1/import-tasks', params);
}

/** GET /api/v1/import-tasks/{id} 任务详情 */
export function getImportTask(id: number): Promise<ImportTaskVO> {
  return http.get<ImportTaskVO>(`/api/v1/import-tasks/${id}`);
}

/** GET /api/v1/import-tasks/{id}/progress 任务进度（前端 2s 轮询，终态停） */
export function getImportTaskProgress(id: number): Promise<ImportTaskProgressVO> {
  return http.get<ImportTaskProgressVO>(`/api/v1/import-tasks/${id}/progress`);
}

/** GET /api/v1/import-tasks/{id}/items 逐条结果（openapi 1.3.0 新增；含跳过/失败条目） */
export function getImportTaskItems(id: number): Promise<ImportTaskItemVO[]> {
  return http.get<ImportTaskItemVO[]>(`/api/v1/import-tasks/${id}/items`);
}

/** GET /api/v1/import-tasks/{id}/leads 任务生成的线索列表 */
export function getImportTaskLeads(id: number, params?: { page?: number; limit?: number }): Promise<PageResult<LeadVO>> {
  return http.get<PageResult<LeadVO>>(`/api/v1/import-tasks/${id}/leads`, params);
}
