<script setup lang="ts">
/**
 * 侧边导航。按角色过滤：专员看 我的工作台/全部线索/导入线索；主管看 销售看板/全部线索。
 * /tasks/:id 为 hidden 路由，不进菜单；/leads/:id 同理。
 */
import { computed } from 'vue';
import { useRoute } from 'vue-router';
import { DataAnalysis, DocumentAdd, DocumentCopy, Odometer, Tickets } from '@element-plus/icons-vue';
import { useUserStore } from '@/stores/user';

const route = useRoute();
const userStore = useUserStore();

// PRD 附录 C.2：/workbench 与 /leads/import* 仅销售专员可见，
// 主管不显示这些入口（直接访问也会被路由守卫挡到 /403）
const showSpecialistOnly = computed(() => userStore.isSpecialist);
const showSupervisorOnly = computed(() => userStore.role === 'SUPERVISOR');

const activePath = computed(() => {
  if (route.path.startsWith('/tasks/')) return '/leads/import/batch';
  if (route.path.startsWith('/leads/import')) return '/leads/import';
  if (route.path.startsWith('/leads')) return '/leads';
  if (route.path.startsWith('/workbench')) return '/workbench';
  return route.path;
});
</script>

<template>
  <nav class="sidebar">
    <div class="sidebar__brand">
      <span class="sidebar__logo" aria-hidden="true">LI</span>
      <span class="sidebar__title">销售线索智能识别</span>
    </div>

    <el-menu class="sidebar__menu" :default-active="activePath" router>
      <el-menu-item v-if="showSpecialistOnly" index="/workbench">
        <el-icon :size="20"><Odometer /></el-icon>
        <span>我的待跟进</span>
      </el-menu-item>
      <el-menu-item v-if="showSupervisorOnly" index="/dashboard">
        <el-icon :size="20"><DataAnalysis /></el-icon>
        <span>销售看板</span>
      </el-menu-item>
      <el-menu-item index="/leads">
        <el-icon :size="20"><Tickets /></el-icon>
        <span>全部线索</span>
      </el-menu-item>
      <el-menu-item v-if="showSpecialistOnly" index="/leads/import">
        <el-icon :size="20"><DocumentAdd /></el-icon>
        <span>粘贴导入</span>
      </el-menu-item>
      <el-menu-item v-if="showSpecialistOnly" index="/leads/import/batch">
        <el-icon :size="20"><DocumentCopy /></el-icon>
        <span>批量导入</span>
      </el-menu-item>
    </el-menu>
  </nav>
</template>

<style scoped>
.sidebar {
  display: flex;
  flex-direction: column;
  height: 100%;
}

.sidebar__brand {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  height: 56px;
  padding: 0 var(--space-4);
  border-bottom: 1px solid var(--border-soft);
}

.sidebar__logo {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: var(--radius-md);
  background: var(--primary);
  color: var(--primary-on);
  font-size: var(--text-xs);
  font-weight: var(--fw-semibold);
  letter-spacing: 0.02em;
}

.sidebar__title {
  font-size: var(--text-base);
  font-weight: var(--fw-semibold);
  letter-spacing: -0.01em;
  color: var(--fg);
}

.sidebar__menu {
  flex: 1;
  border-right: none;
  padding-top: var(--space-2);
}

.sidebar__menu :deep(.el-menu-item) {
  height: 40px;
  line-height: 40px;
  font-size: var(--text-base);
  color: var(--fg-2);
}

.sidebar__menu :deep(.el-menu-item.is-active) {
  color: var(--primary);
  background: var(--primary-subtle);
  font-weight: var(--fw-medium);
}

.sidebar__menu :deep(.el-menu-item:hover) {
  background: var(--surface-2);
}
</style>
