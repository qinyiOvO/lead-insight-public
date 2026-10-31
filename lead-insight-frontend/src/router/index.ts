/**
 * 路由表 —— 路径以 UIUX §7.1 IA 为唯一事实来源（共 11 条）。
 *
 * R3 全量注册 10 条业务路由 + 2 个错误页（/ 重定向不计）：
 *   1. /login                  LoginView
 *   2. /                       MainLayout（redirect 按角色分流，复用 homePathFor：专员->/workbench，主管->/dashboard）
 *   3. /workbench              WorkbenchView（仅专员）
 *   4. /leads                  LeadListView
 *   5. /leads/import           LeadImportView（单条粘贴导入，仅专员，PRD C.2）
 *   6. /leads/import/batch     LeadImportBatchView（批量导入，仅专员；角色待设计师裁定，暂按 §7.1 专员视角）
 *   7. /leads/:id              LeadDetailView
 *   8. /tasks/:id              ImportTaskView（任务进度，仅专员；同待裁定）
 *   9. /dashboard              DashboardView（销售看板，仅主管；专员访问->/403 = AC-05 字面场景）
 *  10. /403                    ForbiddenView（居中单列，不在 Layout 内）
 *  11. /:pathMatch(.*)*        NotFoundView（居中单列，不在 Layout 内）
 */
import { createRouter, createWebHistory } from 'vue-router';
import type { RouteRecordRaw } from 'vue-router';
import type { UserRole } from '@/types/user';
import { homePathFor, registerGuards } from '@/router/guard';
import { setRouterRef } from '@/router/navigation';
import { useUserStore } from '@/stores/user';

declare module 'vue-router' {
  interface RouteMeta {
    /** 面包屑与 document.title 使用 */
    title?: string;
    /** 免登录访问（/login、/403、404 兜底） */
    public?: boolean;
    /** 允许访问的角色；缺省表示登录后均可访问 */
    roles?: UserRole[];
    /** 不出现在侧边导航 */
    hidden?: boolean;
  }
}

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/login/LoginView.vue'),
    meta: { title: '登录', public: true, hidden: true },
  },
  {
    path: '/',
    component: () => import('@/layout/MainLayout.vue'),
    // G-1：根路径按角色分流，与 F-3 同口径。复用 homePathFor() 作为单一事实来源，
    // 不在路由表里写第二份角色判断。useUserStore 必须在函数体内调用（导航期），
    // 不能模块顶层调（此时 pinia 尚未 install）。
    redirect: () => {
      const userStore = useUserStore();
      return homePathFor(userStore.role);
    },
    children: [
      {
        path: 'workbench',
        name: 'workbench',
        component: () => import('@/views/workbench/WorkbenchView.vue'),
        // PRD 附录 C.2：/workbench 仅销售专员可见（主管访问 -> /403）
        meta: { title: '我的待跟进', roles: ['SPECIALIST'] },
      },
      {
        path: 'leads',
        name: 'leads',
        component: () => import('@/views/lead/LeadListView.vue'),
        meta: { title: '线索管理', roles: ['SPECIALIST', 'SUPERVISOR'] },
      },
      {
        path: 'leads/import',
        name: 'lead-import',
        component: () => import('@/views/lead/LeadImportView.vue'),
        // PRD C.2：单条粘贴导入仅销售专员可见（导入是销售动作，主管禁止）
        meta: { title: '单条粘贴导入', roles: ['SPECIALIST'], hidden: true },
      },
      {
        path: 'leads/import/batch',
        name: 'lead-import-batch',
        component: () => import('@/views/lead/LeadImportBatchView.vue'),
        // UIUX §7.1 将批量导入列在专员视角；角色若与设计师裁定不一致，只改这一行
        meta: { title: '批量导入', roles: ['SPECIALIST'] },
      },
      {
        path: 'leads/:id',
        name: 'lead-detail',
        component: () => import('@/views/lead/LeadDetailView.vue'),
        meta: { title: '线索详情', roles: ['SPECIALIST', 'SUPERVISOR'], hidden: true },
      },
      {
        path: 'tasks/:id',
        name: 'import-task',
        component: () => import('@/views/task/ImportTaskView.vue'),
        meta: { title: '批量导入任务', roles: ['SPECIALIST'], hidden: true },
      },
      {
        path: 'dashboard',
        name: 'dashboard',
        component: () => import('@/views/dashboard/DashboardView.vue'),
        // PRD AC-05 字面场景：专员访问 /dashboard -> /403
        meta: { title: '销售看板', roles: ['SUPERVISOR'] },
      },
    ],
  },
  {
    path: '/403',
    name: 'forbidden',
    component: () => import('@/views/error/ForbiddenView.vue'),
    meta: { title: '无权限访问', public: true, hidden: true },
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    component: () => import('@/views/error/NotFoundView.vue'),
    meta: { title: '页面不存在', public: true, hidden: true },
  },
];

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 }),
});

registerGuards(router);
// 注入给 api 层使用（401/403 跳转），避免 http.ts 直接 import router 形成模块环
setRouterRef(router);

export default router;
