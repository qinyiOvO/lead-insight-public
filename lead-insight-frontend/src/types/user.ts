/**
 * 用户与认证类型 —— 严格照 openapi.yaml v1.2.1 手写。
 */

/** 销售专员 SPECIALIST / 销售主管 SUPERVISOR */
export type UserRole = 'SPECIALIST' | 'SUPERVISOR';

/** openapi UserVO */
export interface UserVO {
  id: number;
  username: string;
  realName: string;
  role: UserRole;
}

/** openapi LoginRequest */
export interface LoginRequest {
  username: string;
  password: string;
}

/** openapi LoginResponse */
export interface LoginResponse {
  token: string;
  tokenType: string;
  expiresIn: number;
  user: UserVO;
}
