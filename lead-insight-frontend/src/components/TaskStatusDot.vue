<script setup lang="ts">
/**
 * TaskStatusDot —— 批量导入任务五态指示（UIUX §7.5.6）。
 * 形态与 StatusDot 同款（8px 圆点 + 6px 间距），但**必须**用 --task-* 独立 Token：
 * 任务态与线索态同形不同义，靠独立 Token + danger 红的 FAILED + 伴随进度数值区分。
 */
import { computed } from 'vue';
import type { ImportTaskStatus } from '@/types/import-task';
import { IMPORT_TASK_STATUS_LABEL } from '@/utils/format';

const props = defineProps<{ status: ImportTaskStatus }>();

const TOKEN: Record<ImportTaskStatus, string> = {
  PENDING: '--task-pending',
  RUNNING: '--task-running',
  SUCCESS: '--task-success',
  PARTIAL: '--task-partial',
  FAILED: '--task-failed',
};

const dotColor = computed(() => `var(${TOKEN[props.status]})`);
const label = computed(() => IMPORT_TASK_STATUS_LABEL[props.status]);
</script>

<template>
  <span class="task-status-dot">
    <span class="task-status-dot__dot" :style="{ background: dotColor }" aria-hidden="true" />
    <span class="task-status-dot__text">{{ label }}</span>
  </span>
</template>

<style scoped>
.task-status-dot {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.task-status-dot__dot {
  flex: none;
  width: 8px;
  height: 8px;
  border-radius: var(--radius-full);
}

.task-status-dot__text {
  font-size: var(--text-sm);
  color: var(--fg-2);
}
</style>
