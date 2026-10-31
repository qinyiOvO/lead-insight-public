/** 认证接口 —— 路径以 openapi.yaml v1.2.1 为唯一真源 */
import { http, type RequestOptions } from '@/api/http';
import type { LoginRequest, LoginResponse, UserVO } from '@/types/user';

/** 账号密码登录。silent=true 由登录页就地展示后端 message（后端故意不区分用户不存在/密码错误，AC-03） */
export function login(payload: LoginRequest, options?: RequestOptions): Promise<LoginResponse> {
  return http.post<LoginResponse>('/api/v1/auth/login', payload, options);
}

export function logout(): Promise<unknown> {
  return http.post<unknown>('/api/v1/auth/logout');
}

export function getMe(): Promise<UserVO> {
  return http.get<UserVO>('/api/v1/auth/me');
}
