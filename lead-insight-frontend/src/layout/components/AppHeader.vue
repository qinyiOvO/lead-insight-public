<script setup lang="ts">
/** 顶栏：左侧面包屑 + 右侧当前用户与登出 */
import { computed } from 'vue';
import { useRouter } from 'vue-router';
import { ArrowDown, User } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { useUserStore } from '@/stores/user';
import { ROLE_LABEL } from '@/utils/format';
import AppBreadcrumb from '@/layout/components/AppBreadcrumb.vue';

const router = useRouter();
const userStore = useUserStore();

const roleText = computed(() => (userStore.role ? ROLE_LABEL[userStore.role] : ''));

async function handleCommand(command: string): Promise<void> {
  if (command !== 'logout') return;
  await userStore.logout();
  ElMessage.success('已退出登录');
  await router.replace('/login');
}
</script>

<template>
  <div class="app-header">
    <AppBreadcrumb />

    <el-dropdown trigger="click" @command="handleCommand">
      <button class="app-header__user" type="button" aria-label="账号菜单">
        <el-icon :size="16"><User /></el-icon>
        <span class="app-header__name">{{ userStore.displayName }}</span>
        <span v-if="roleText" class="app-header__role">{{ roleText }}</span>
        <el-icon :size="16"><ArrowDown /></el-icon>
      </button>
      <template #dropdown>
        <el-dropdown-menu>
          <el-dropdown-item command="logout">退出登录</el-dropdown-item>
        </el-dropdown-menu>
      </template>
    </el-dropdown>
  </div>
</template>

<style scoped>
.app-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
}

.app-header__user {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
  height: 32px;
  padding: 0 var(--space-2);
  border: 1px solid transparent;
  border-radius: var(--radius-md);
  background: transparent;
  color: var(--fg-2);
  font-size: var(--text-base);
  cursor: pointer;
  transition: background-color var(--duration-fast) var(--ease-standard);
}

.app-header__user:hover {
  background: var(--surface-2);
  border-color: var(--border);
}

.app-header__name {
  font-weight: var(--fw-medium);
  color: var(--fg);
}

.app-header__role {
  color: var(--fg-3);
  font-size: var(--text-sm);
}
</style>
