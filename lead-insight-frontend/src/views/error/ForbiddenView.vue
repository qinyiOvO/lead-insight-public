<script setup lang="ts">
/** 无权限提示页 /403 —— PRD AC-05 的机械判定点。居中单列，不在 Layout 内。 */
import { useRouter } from 'vue-router';
import { Lock } from '@element-plus/icons-vue';
import { useUserStore } from '@/stores/user';

const router = useRouter();
// E-10 + UIUX §8.5（R3 定稿）：返回入口按角色过滤。
// 主管：主按钮「返回销售看板」（/dashboard 主管专属落点），次按钮「返回线索列表」；
// 专员：主按钮「返回待跟进工作台」（/workbench 仅专员可见，主管点它会被守卫再次弹回 /403）。
const userStore = useUserStore();
</script>

<template>
  <div class="error-page">
    <div class="error-page__inner">
      <el-icon :size="48" class="error-page__icon"><Lock /></el-icon>
      <h1 class="error-page__title">当前账号无权访问该页面</h1>
      <p class="error-page__desc">
        你登录的角色不具备该页面的访问权限。请返回你有权限的页面继续操作。
      </p>
      <div class="error-page__actions">
        <template v-if="!userStore.isLoggedIn">
          <el-button type="primary" @click="router.replace('/login')">返回登录页</el-button>
        </template>
        <template v-else>
          <el-button v-if="userStore.isSpecialist" type="primary" @click="router.replace('/workbench')">
            返回待跟进工作台
          </el-button>
          <el-button v-else type="primary" @click="router.replace('/dashboard')">
            返回销售看板
          </el-button>
          <el-button @click="router.replace('/leads')">
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
