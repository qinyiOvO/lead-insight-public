import { createApp } from 'vue';
import { createPinia } from 'pinia';
import ElementPlus from 'element-plus';
import zhCn from 'element-plus/es/locale/lang/zh-cn';
import * as ElementPlusIconsVue from '@element-plus/icons-vue';

// 顺序要求：先 Element Plus 默认样式，再本系统设计令牌，使令牌中的 --el-* 桥接生效（覆盖 EP 默认值）
import 'element-plus/dist/index.css';
import '@/styles/index.css';

import App from '@/App.vue';
import router from '@/router';

const app = createApp(App);

app.use(createPinia());
app.use(router);
app.use(ElementPlus, { locale: zhCn });

// 图标全量注册（P0-1：全项目唯一图标来源，禁止 emoji）
Object.entries(ElementPlusIconsVue).forEach(([name, component]) => {
  app.component(name, component);
});

app.mount('#app');
