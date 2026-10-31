import { fileURLToPath } from 'node:url';
import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';

// 前端开发端口 5173；/api 代理到后端 8080（避免跨域）。
// 生产部署由 Nginx 托管 dist 并反代 /api，不在此写死域名。
//
// 默认值不变；端口与代理目标可由环境变量覆盖，用于并行开隔离验证通道：
//   VITE_DEV_PORT=5174 VITE_PROXY_TARGET=http://localhost:8081 npm run dev
// 这样多个验证任务各自打独立后端/数据库，不会互相踩，也不会踩主开发环境。
const env: Record<string, string | undefined> =
  (globalThis as { process?: { env?: Record<string, string | undefined> } }).process?.env ?? {};
const devPort = Number(env.VITE_DEV_PORT ?? 5173);
const proxyTarget = env.VITE_PROXY_TARGET ?? 'http://localhost:8080';

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: devPort,
    host: 'localhost',
    proxy: {
      '/api': {
        target: proxyTarget,
        changeOrigin: true,
      },
    },
  },
  build: {
    outDir: 'dist',
    sourcemap: false,
    chunkSizeWarningLimit: 1500,
  },
});
