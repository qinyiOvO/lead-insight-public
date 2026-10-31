<script setup lang="ts">
/** 面包屑：按路由前缀映射，避免依赖后端菜单数据 */
import { computed } from 'vue';
import { useRoute } from 'vue-router';

const route = useRoute();

const crumbs = computed<string[]>(() => {
  const path = route.path;
  if (path.startsWith('/workbench')) return ['我的待跟进'];
  if (path.startsWith('/leads')) {
    return route.name === 'lead-detail' ? ['线索管理', '线索详情'] : ['线索管理'];
  }
  const title = route.meta.title;
  return typeof title === 'string' ? [title] : [];
});
</script>

<template>
  <el-breadcrumb class="app-breadcrumb" separator="/">
    <el-breadcrumb-item v-for="(item, index) in crumbs" :key="index">
      {{ item }}
    </el-breadcrumb-item>
  </el-breadcrumb>
</template>

<style scoped>
.app-breadcrumb {
  font-size: var(--text-base);
}

.app-breadcrumb :deep(.el-breadcrumb__inner) {
  color: var(--fg-3);
  font-weight: var(--fw-regular);
}

.app-breadcrumb :deep(.el-breadcrumb__item:last-child .el-breadcrumb__inner) {
  color: var(--fg);
  font-weight: var(--fw-medium);
}
</style>
