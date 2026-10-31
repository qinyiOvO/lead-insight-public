/**
 * axios 实例与统一响应处理。
 *
 * 约定：
 * 1. 请求拦截注入 `Authorization: Bearer <token>`。
 * 2. 响应拦截统一解包后端 `{code,message,data}`：code===0 时把 data 交给调用方；
 *    非 0 时弹 ElMessage.error 并 reject（抛出 BizError，携带 code）。
 *    其中**业务错误码白名单**（见下）透传后端受控中文文案；其余码（含 50000 与未知码）
 *    一律替换为中文兜底文案，不透传后端 message（DEF-08 / AC-34：不暴露堆栈与内部细节）。
 * 3. 认证类错误（401 / 40100 / 40101）-> 清 token 并跳 /login（AC-06）。
 *    权限类错误（403 / 40300）-> 跳 /403（AC-05）。
 * 4. `silent: true` 的请求不自动弹提示，由调用方就地展示 message（已经兜底文案过滤）。
 *
 * 开发自查：仅当 import.meta.env.DEV 且 VITE_USE_MOCK==='true' 时改走内存 Mock，
 * 该分支在生产构建中被静态消除，生产代码路径不包含 Mock。
 */
import axios from 'axios';
import type { AxiosError, AxiosInstance, AxiosRequestConfig, AxiosResponse } from 'axios';
import { ElMessage } from 'element-plus';
import { BizError } from '@/api/error';
import { clearAuth, getToken } from '@/utils/auth';
import { navigateTo } from '@/router/navigation';
import type { R } from '@/types/common';

const UNAUTHORIZED_CODES = new Set([401, 40100, 40101]);
const FORBIDDEN_CODES = new Set([403, 40300]);

/**
 * 业务错误码白名单（DEF-08 / AC-34，对齐后端 ResultCode 枚举）：
 * 这些码的 message 是后端受控的中文业务文案——40001 字段级校验、40400 资源不存在、
 * 40901 内容重复、42201 非法状态迁移、42900 限流、50301 模型不可用，以及认证/权限类提示，
 * 对用户有可读性与行动指导价值，允许透传；
 * 其余码（50000 系统内部错误及任何未知码）一律走中文兜底，防止堆栈/包名/SQL 经 message 外泄。
 */
const BUSINESS_MESSAGE_CODES = new Set([40001, 40100, 40101, 40300, 40400, 40901, 42201, 42900, 50301]);

/** 非业务码的统一中文兜底文案（替代后端原始 message） */
const FALLBACK_ERROR_MESSAGE = '服务异常，请稍后重试';
/** 网络层异常（无 R 响应体：断网/超时/CORS 等）的统一中文兜底文案 */
const NETWORK_ERROR_MESSAGE = '网络异常，请稍后重试';

/** 非业务码不透传后端 message，统一中文兜底（DEF-08 / AC-34） */
function resolveMessage(code: number, message: string): string {
  return BUSINESS_MESSAGE_CODES.has(code) && message ? message : FALLBACK_ERROR_MESSAGE;
}

function buildError(code: number, message: string, silent?: boolean): BizError {
  const text = resolveMessage(code, message);
  const err = new BizError(code, text);
  if (UNAUTHORIZED_CODES.has(code)) {
    clearAuth();
    navigateTo('/login');
    return err;
  }
  if (FORBIDDEN_CODES.has(code)) {
    navigateTo('/403');
    return err;
  }
  if (!silent) {
    ElMessage.error(text);
  }
  return err;
}

/** 尝试从响应体中取出统一包装；非 R 结构返回 null */
function unwrap(body: unknown): R | null {
  if (body && typeof body === 'object' && 'code' in body) {
    const candidate = body as R;
    if (typeof candidate.code === 'number') return candidate;
  }
  return null;
}

// baseURL 只承载「源」，不承载路径前缀：
// api 层的 URL 已逐字对齐契约（/api/v1/**），若 baseURL 再带 /api 会被 axios 拼成 /api/api/v1/**。
// 开发由 Vite 代理、生产由 Nginx 反代 /api 到后端，故默认同源空串。
export const service: AxiosInstance = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL ?? '',
  timeout: 20000,
  headers: { 'Content-Type': 'application/json' },
});

service.interceptors.request.use((config) => {
  const token = getToken();
  if (token) {
    config.headers.set('Authorization', `Bearer ${token}`);
  }
  return config;
});

service.interceptors.response.use(
  (response: AxiosResponse) => {
    const silent = response.config.silent;
    const body = unwrap(response.data);
    if (!body) return response.data;
    if (body.code === 0) return body.data as unknown as AxiosResponse;
    throw buildError(body.code, body.message, silent);
  },
  (error: AxiosError) => {
    const silent = error.config?.silent;
    const body = unwrap(error.response?.data);
    if (body) throw buildError(body.code, body.message, silent);

    const status = error.response?.status;
    if (status && (UNAUTHORIZED_CODES.has(status) || FORBIDDEN_CODES.has(status))) {
      throw buildError(status, status === 403 ? '无权访问该资源' : '登录状态已失效', silent);
    }
    if (!silent) {
      // 网络层异常同样只显示中文兜底，不透传 axios 的英文 message（DEF-08 / AC-34）
      ElMessage.error(NETWORK_ERROR_MESSAGE);
    }
    throw error;
  },
);

type RequestConfig = AxiosRequestConfig;

function request<T>(config: RequestConfig): Promise<T> {
  if (import.meta.env.DEV && import.meta.env.VITE_USE_MOCK === 'true') {
    return import('@/mocks').then((mod) => mod.mockRequest<T>(config));
  }
  // 响应拦截器已把 R.data 解包并返回，故此处按业务类型断言（axios 1.20 的 request 泛型不直接暴露该形态）
  return service.request(config) as unknown as Promise<T>;
}

export interface RequestOptions {
  silent?: boolean;
}

export const http = {
  get<T>(url: string, params?: Record<string, unknown>, options?: RequestOptions): Promise<T> {
    return request<T>({ method: 'get', url, params, ...options });
  },
  post<T>(url: string, data?: unknown, options?: RequestOptions): Promise<T> {
    return request<T>({ method: 'post', url, data, ...options });
  },
  patch<T>(url: string, data?: unknown, options?: RequestOptions): Promise<T> {
    return request<T>({ method: 'patch', url, data, ...options });
  },
  delete<T>(url: string, options?: RequestOptions): Promise<T> {
    return request<T>({ method: 'delete', url, ...options });
  },
};
