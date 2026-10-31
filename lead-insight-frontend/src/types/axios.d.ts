import 'axios';

declare module 'axios' {
  /**
   * 扩展 axios 请求配置：silent=true 时响应拦截器不自动弹 ElMessage，
   * 由调用方自行处理错误文案（例如登录页就地红字、状态推进页需展示后端原始 message）。
   */
  export interface AxiosRequestConfig {
    silent?: boolean;
  }
}
