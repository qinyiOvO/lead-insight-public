<script setup lang="ts">
/** 404 兜底页。居中单列，不在 Layout 内。 */
import { useRouter } from 'vue-router';
import { Search } from '@element-plus/icons-vue';
import { useUserStore } from '@/stores/user';

const router = useRouter();
// E-10：返回入口按角色过滤，口径与 /403 一致
const userStore = useUserStore();
</script>

<template>
  <div class="error-page">
    <div class="error-page__inner">
      <el-icon :size="48" class="error-page__icon"><Search /></el-icon>
      <h1 class="error-page__title">页面不存在</h1>
      <p class="error-page__desc">你访问的页面可能已被移除，或链接有误。</p>
      <div class="error-page__actions">
        <template v-if="!userStore.isLoggedIn">
          <el-button type="primary" @click="router.replace('/login')">返回登录页</el-button>
        </template>
        <template v-else>
          <el-button v-if="userStore.isSpecialist" type="primary" @click="router.replace('/workbench')">
            返回待跟进工作台
          </el-button>
          <el-button :type="userStore.isSpecialist ? 'default' : 'primary'" @click="router.replace('/leads')">
            返回线索列表
          </el-button>
        </template>
      </div>
    </div>
  </div>
</template>

<style scoped>
.error-page {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100%;
  height: 100%;
  padding: var(--space-10);
  background: var(--bg);
}

.error-page__inner {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-3);
  max-width: 460px;
  text-align: center;
}

.error-page__icon {
  color: var(--meta);
}

.error-page__title {
  font-size: var(--text-lg);
  font-weight: var(--fw-semibold);
  color: var(--fg);
}

.error-page__desc {
  font-size: var(--text-base);
  color: var(--fg-2);
  line-height: var(--leading-relaxed);
}

.error-page__actions {
  display: flex;
  gap: var(--space-2);
  margin-top: var(--space-2);
}
</style>
