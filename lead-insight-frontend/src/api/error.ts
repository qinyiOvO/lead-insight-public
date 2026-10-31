/**
 * 业务错误对象：拦截器把后端 {code,message} 非 0 的响应包装成本类抛出，
 * 调用方可通过 `err.code` 做分支（例如 42201 非法状态迁移）。
 * 单独成文件，避免 http.ts 与 mocks 之间的循环依赖。
 */
export class BizError extends Error {
  code: number;

  constructor(code: number, message: string) {
    super(message);
    this.name = 'BizError';
    this.code = code;
  }
}

/** 从任意抛出物中取出错误码与文案（供视图统一展示） */
export function toBizMessage(err: unknown): { code: number; message: string } {
  if (err instanceof BizError) return { code: err.code, message: err.message };
  if (err instanceof Error) return { code: -1, message: err.message };
  return { code: -1, message: '请求失败，请稍后重试' };
}
