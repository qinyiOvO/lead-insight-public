<script setup lang="ts">
/**
 * PriorityTag —— 优先级标签。
 * 三通道表达（色 + 方向图标 + 文字），色盲友好；颜色只读设计令牌。
 * 规格：高 20px / 圆角 4px / padding 0 6px / 字号 12px / 图标 12px + 4px 间距。
 * 本项目为中文语境：高=Top 红、中=Minus 琥珀、低=Bottom 灰（非欧美涨跌色语义）。
 */
import { computed } from 'vue';
import { Bottom, Minus, Top } from '@element-plus/icons-vue';
import type { PriorityLevel } from '@/types/lead';
import { PRIORITY_LABEL } from '@/utils/format';

const props = defineProps<{
  level: PriorityLevel;
  /** 优先级分数；传入时以等宽字体附在等级之后（表格“优先级”列同时展示分数与等级） */
  score?: number | null;
}>();

const ICONS = { HIGH: Top, MEDIUM: Minus, LOW: Bottom } as const;

const tone = computed(() => props.level.toLowerCase());
const icon = computed(() => ICONS[props.level]);
const label = computed(() => PRIORITY_LABEL[props.level]);
const hasScore = computed(() => typeof props.score === 'number' && Number.isFinite(props.score));
</script>

<template>
  <span class="priority-tag" :class="`is-${tone}`">
    <el-icon :size="12" class="priority-tag__icon"><component :is="icon" /></el-icon>
    <span class="priority-tag__label">{{ label }}</span>
    <span v-if="hasScore" class="priority-tag__score">{{ score }}</span>
  </span>
</template>

<style scoped>
.priority-tag {
  display: inline-flex;
  align-items: center;
  height: 20px;
  padding: 0 6px;
  gap: 4px;
  border-radius: var(--radius-sm);
  border: 1px solid transparent;
  font-size: var(--text-xs);
  font-weight: var(--fw-medium);
  line-height: 1;
  white-space: nowrap;
}

.priority-tag__score {
  font-family: var(--font-mono);
  font-variant-numeric: tabular-nums;
  opacity: 0.85;
}

.priority-tag.is-high {
  color: var(--priority-high-text);
  background: var(--priority-high-bg);
  border-color: var(--priority-high-border);
}

.priority-tag.is-medium {
  color: var(--priority-medium-text);
  background: var(--priority-medium-bg);
  border-color: var(--priority-medium-border);
}

.priority-tag.is-low {
  color: var(--priority-low-text);
  background: var(--priority-low-bg);
  border-color: var(--priority-low-border);
}
</style>
