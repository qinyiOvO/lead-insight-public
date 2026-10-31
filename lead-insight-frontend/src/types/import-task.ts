/**
 * 批量导入任务类型 —— 以 openapi 1.3.0 为唯一真源（T3-02/T3-03/T3-04）。
 *
 * 任务级 status 五态（PENDING/RUNNING/SUCCESS/PARTIAL/FAILED）与线索四态是两套枚举，
 * 视觉承载用 --task-* Token（UIUX §7.5.6：同形不同义必须可分，禁复用 --status-*）。
 * 条目级 itemStatus（openapi 1.3.1）六值：PENDING/RUNNING 中间态——任务运行中轮询
 * 明细表时条目不能凭空消失，未处理的条目仍要渲染；DEGRADED 为无 key/模型降级态，
 * 线索已创建（leadId 有值），仅 AI 识别未完成、转人工补全，不是失败。
 */
export type ImportTaskStatus = 'PENDING' | 'RUNNING' | 'SUCCESS' | 'PARTIAL' | 'FAILED';

export type ImportItemStatus = 'PENDING' | 'RUNNING' | 'SUCCESS' | 'DUPLICATE' | 'FAILED' | 'DEGRADED';

export type BatchImportSource = 'BATCH' | 'FILE';

export interface BatchImportRequest {
  /** BATCH=批量粘贴；FILE=前端读取的文本文件（文件本体不上传，R-12） */
  importSource?: BatchImportSource;
  /** 按分隔线切分后的邮件文本列表，1..200 条 */
  textList: string[];
}

export interface ImportTaskVO {
  id: number;
  /** 任务号（如 T-0087），仅展示用；路由参数用数值 id（契约裁定） */
  taskNo: string;
  /** 入口 B 的文件名（粘贴入口为 null） */
  fileName: string | null;
  importSource: string;
  totalCount: number;
  processedCount: number;
  successCount: number;
  duplicateCount: number;
  failCount: number;
  status: ImportTaskStatus;
  startedAt: string | null;
  finishedAt: string | null;
  createdAt: string;
}

export interface ImportTaskProgressVO {
  taskId: number;
  status: ImportTaskStatus;
  total: number;
  processed: number;
  success: number;
  duplicate: number;
  fail: number;
  /** 0-100 整数百分比 */
  percent: number;
}

/** 任务逐条结果（openapi 1.3.1 /import-tasks/{id}/items） */
export interface ImportTaskItemVO {
  /** 提交序号，从 1 起 */
  seq: number;
  itemStatus: ImportItemStatus;
  /** SUCCESS / DEGRADED（降级建档）时指向生成的线索 */
  leadId: number | null;
  customerName: string | null;
  /** 跳过/失败原因说明 */
  reason: string | null;
}
