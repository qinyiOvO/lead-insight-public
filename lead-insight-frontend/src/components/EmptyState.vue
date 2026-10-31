<script setup lang="ts">
/**
 * EmptyState —— 列表 / 区块空态引导。
 * 文案必须具体（禁止 Lorem / “暂无数据”这类空洞占位），操作入口通过 #actions 插槽传入。
 */
import type { Component } from 'vue';
import { Files } from '@element-plus/icons-vue';

defineProps<{
  title: string;
  description?: string;
  /** Element Plus 图标组件，缺省用 Files */
  icon?: Component;
}>();
</script>

<template>
  <div class="empty-state">
    <el-icon :size="40" class="empty-state__icon">
      <component :is="icon ?? Files" />
    </el-icon>
    <p class="empty-state__title">{{ title }}</p>
    <p v-if="description" class="empty-state__desc">{{ description }}</p>
    <div class="empty-state__actions">
      <slot name="actions" />
    </div>
  </div>
</template>

<style scoped>
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--space-2);
  padding: var(--space-12) var(--space-6);
  text-align: center;
}

.empty-state__icon {
  color: var(--meta);
}

.empty-state__title {
  font-size: var(--text-md);
  font-weight: var(--fw-medium);
  color: var(--fg);
}

.empty-state__desc {
  max-width: 48ch;
  font-size: var(--text-sm);
  color: var(--fg-3);
}

.empty-state__actions {
  display: flex;
  gap: var(--space-2);
  margin-top: var(--space-2);
}
</style>
