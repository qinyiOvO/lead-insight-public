/// <reference types="vite/client" />
/// <reference types="element-plus/global" />

declare module '*.vue' {
  import type { DefineComponent } from 'vue';
  const component: DefineComponent<Record<string, unknown>, Record<string, unknown>, unknown>;
  export default component;
}

interface ImportMetaEnv {
  /** 接口前缀，开发/生产均为 /api（由 Vite 代理或 Nginx 反代） */
  readonly VITE_API_BASE_URL: string;
  /** 仅开发环境有效的 Mock 开关，'true' 时启用内存 Mock。默认 false */
  readonly VITE_USE_MOCK?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
