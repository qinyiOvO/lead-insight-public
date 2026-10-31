/**
 * axios 实例与统一响应处理。
 *
 * 约定：
 * 1. 请求拦截注入 `Authorization: Bearer <token>`。
 * 2. 响应拦截统一解包后端 `{code,message,data}`：code===0 时把 data 交给调用方；
 *    非 0 时弹 ElMessage.error(message) 并 reject（抛出 BizError，携带 code）。
 * 3. 认证类错误（401 / 40100 / 40101）-> 清 token 并跳 /login（AC-06）。
 *    权限类错误（403 / 40300）-> 跳 /403（AC-05）。
 * 4. `silent: true` 的请求不自动弹提示，由调用方就地展示后端 message。
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

function buildError(code: number, message: string, silent?: boolean): BizError {
  const err = new BizError(code, message);
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
    ElMessage.error(message || '请求失败，请稍后重试');
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
      ElMessage.error(error.message || '网络异常，请稍后重试');
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
