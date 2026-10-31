/**
 * 路由跳转的注入式引用。
 * 目的：让 api/http.ts 在 401/403 时跳转，而不直接 import '@/router'（会形成
 * http -> router -> guard -> store -> api -> http 的模块环）。
 * router/index.ts 创建实例后调用 setRouterRef 注入；http.ts 只依赖本文件。
 */
import type { Router } from 'vue-router';

let routerRef: Router | null = null;

export function setRouterRef(router: Router): void {
  routerRef = router;
}

/** 跳转到指定路径（同路径不重复跳转） */
export function navigateTo(path: string): void {
  const router = routerRef;
  if (!router) return;
  if (router.currentRoute.value.path === path) return;
  void router.replace(path);
}
