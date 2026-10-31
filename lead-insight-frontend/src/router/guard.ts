/**
 * 路由守卫：登录态 + 角色。
 * - 未登录访问非白名单路由 -> 跳 /login 并带 redirect 查询参数（AC-04）
 * - 已登录访问 /login -> 跳角色主页
 * - meta.roles 与当前角色不匹配 -> 跳 /403（AC-05）
 */
import type { Router } from 'vue-router';
import { useUserStore } from '@/stores/user';
import type { UserRole } from '@/types/user';

/** 免登录白名单（meta.public 之外的兜底） */
const PUBLIC_PATHS = new Set(['/login', '/403']);

const APP_TITLE = '销售线索智能识别系统';

/**
 * 登录后的首页（F-3 裁定 + A-7/§7.1 里程碑口径）：
 * - SPECIALIST -> /workbench（待跟进工作台）
 * - SUPERVISOR -> /dashboard（R3 起：销售看板为主管首屏，UIUX §7.1 重定向备注）
 * - 未登录     -> /dashboard（由未登录守卫先拦去 /login，此处仅为兜底）
 */
export function homePathFor(role: UserRole | null): string {
  if (role === 'SPECIALIST') return '/workbench';
  return '/dashboard';
}

export function registerGuards(router: Router): void {
  router.beforeEach((to) => {
    const userStore = useUserStore();
    const isPublic = to.meta.public === true || PUBLIC_PATHS.has(to.path);

    if (!userStore.isLoggedIn) {
      if (isPublic) return true;
      return { path: '/login', query: { redirect: to.fullPath } };
    }

    if (to.path === '/login') {
      return { path: homePathFor(userStore.role) };
    }

    const required = to.meta.roles as UserRole[] | undefined;
    if (required && required.length > 0 && userStore.role && !required.includes(userStore.role)) {
      return { path: '/403' };
    }
    return true;
  });

  router.afterEach((to) => {
    const title = to.meta.title;
    document.title = title ? `${title} · ${APP_TITLE}` : APP_TITLE;
  });
}
