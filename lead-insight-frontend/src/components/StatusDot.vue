<script setup lang="ts">
/**
 * StatusDot —— 线索状态指示。
 * 形态约束（UIUX §6.4）：8px 实心圆点 + 6px 间距 + 13px 文字，**不得**做成 el-tag。
 * 无效用中性灰而非红色（无效是业务结果不是错误）。
 */
import { computed } from 'vue';
import type { LeadStatus } from '@/types/lead';
import { STATUS_LABEL } from '@/utils/format';

const props = defineProps<{ status: LeadStatus }>();

const TOKEN: Record<LeadStatus, string> = {
  NEW: '--status-new',
  FOLLOWING: '--status-following',
  WON: '--status-won',
  INVALID: '--status-invalid',
};

const dotColor = computed(() => `var(${TOKEN[props.status]})`);
const label = computed(() => STATUS_LABEL[props.status]);
</script>

<template>
  <span class="status-dot">
    <span class="status-dot__dot" :style="{ background: dotColor }" aria-hidden="true" />
    <span class="status-dot__text">{{ label }}</span>
  </span>
</template>

<style scoped>
.status-dot {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.status-dot__dot {
  flex: none;
  width: 8px;
  height: 8px;
  border-radius: var(--radius-full);
}

.status-dot__text {
  font-size: var(--text-sm);
  color: var(--fg-2);
}
</style>
