/**
 * 登录态本地存取（token + 用户信息）。
 * 单一职责：只读写 localStorage，不含业务判断；业务状态由 stores/user.ts 持有。
 */
import type { UserVO } from '@/types/user';

const TOKEN_KEY = 'lead-insight.token';
const USER_KEY = 'lead-insight.user';

export function getToken(): string {
  return localStorage.getItem(TOKEN_KEY) ?? '';
}

export function setToken(token: string): void {
  localStorage.setItem(TOKEN_KEY, token);
}

export function getStoredUser(): UserVO | null {
  const raw = localStorage.getItem(USER_KEY);
  if (!raw) return null;
  try {
    return JSON.parse(raw) as UserVO;
  } catch {
    localStorage.removeItem(USER_KEY);
    return null;
  }
}

export function setStoredUser(user: UserVO): void {
  localStorage.setItem(USER_KEY, JSON.stringify(user));
}

/** 清空登录态（token 失效、401/403、主动登出均调用） */
export function clearAuth(): void {
  localStorage.removeItem(TOKEN_KEY);
  localStorage.removeItem(USER_KEY);
}
