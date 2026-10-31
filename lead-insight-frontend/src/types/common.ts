/**
 * 通用类型 —— 严格照 openapi.yaml v1.2.1 手写（字段名驼峰）。
 */

/** 统一响应包装 R{code,message,data}；code===0 为成功 */
export interface R<T = unknown> {
  code: number;
  message: string;
  data: T;
}

/** 分页响应包装（openapi PageResult） */
export interface PageResult<T> {
  items: T[];
  total: number;
  page: number;
  limit: number;
  hasMore: boolean;
}

/** 分页请求基础参数 */
export interface PageQuery {
  page?: number;
  limit?: number;
}
