/**
 * 登录态与当前用户（Pinia setup store）。
 * token / user 持久化到 localStorage，刷新后自动恢复（AC-04/AC-06 依赖）。
 */
import { computed, ref } from 'vue';
import { defineStore } from 'pinia';
import { login as loginApi, logout as logoutApi } from '@/api/auth';
import type { LoginRequest, LoginResponse, UserRole, UserVO } from '@/types/user';
import { clearAuth, getStoredUser, getToken, setStoredUser, setToken } from '@/utils/auth';

export const useUserStore = defineStore('user', () => {
  const token = ref<string>(getToken());
  const user = ref<UserVO | null>(getStoredUser());

  const isLoggedIn = computed(() => token.value.length > 0);
  const role = computed<UserRole | null>(() => user.value?.role ?? null);
  const isSupervisor = computed(() => role.value === 'SUPERVISOR');
  const isSpecialist = computed(() => role.value === 'SPECIALIST');
  const displayName = computed(() => user.value?.realName || user.value?.username || '');
  const currentUserId = computed<number | null>(() => user.value?.id ?? null);

  /** 登录成功：写入 token 与用户信息，并落 localStorage */
  async function login(payload: LoginRequest): Promise<LoginResponse> {
    const res = await loginApi(payload, { silent: true });
    token.value = res.token;
    user.value = res.user;
    setToken(res.token);
    setStoredUser(res.user);
    return res;
  }

  /** 清空本地登录态（401/403 拦截器与主动登出共用） */
  function reset(): void {
    clearAuth();
    token.value = '';
    user.value = null;
  }

  /** 主动登出：先尝试通知后端，失败不阻塞前端清理 */
  async function logout(): Promise<void> {
    try {
      if (token.value) await logoutApi();
    } catch {
      // 后端不可达或 token 已失效时忽略，前端仍需登出
    } finally {
      reset();
    }
  }

  function setUser(next: UserVO): void {
    user.value = next;
    setStoredUser(next);
  }

  return {
    token,
    user,
    isLoggedIn,
    role,
    isSupervisor,
    isSpecialist,
    displayName,
    currentUserId,
    login,
    logout,
    reset,
    setUser,
  };
});
