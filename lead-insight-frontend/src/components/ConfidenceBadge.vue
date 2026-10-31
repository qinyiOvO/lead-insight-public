<script setup lang="ts">
/**
 * ConfidenceBadge —— 抽取置信度 / 待复核标记（亮点1 的视觉承载）。
 *
 * 口径（R-2 / R-7 / E-7）：
 * - 档位由系统自算后落库：HIGH=0.9 / MEDIUM=0.7 / LOW=0.4。
 * - 「待复核」= MEDIUM + LOW 两档；LOW 视觉更强（疑似幻觉风险更高）。
 * - E-7：confidenceLevel === null 时**整个徽章不渲染**（连「待复核」都不出），
 *       避免在未评估阶段制造无意义的噪音标签。
 * - E-7：extract_status === FAILED 时显示「已转人工」（解析失败已交由人工处理）。
 */
import { computed } from 'vue';
import { Warning } from '@element-plus/icons-vue';
import type { ConfidenceLevel, ExtractStatus } from '@/types/lead';

const props = defineProps<{
  level?: ConfidenceLevel | null;
  /** 抽取状态：FAILED（解析失败）时覆盖置信度口径，显示「已转人工」 */
  extractStatus?: ExtractStatus | null;
  /** tooltip 中的触发原因（字段缺失 / 原文可回溯率低等），缺省时用档位通用说明 */
  reason?: string;
}>();

type Tone = 'high' | 'medium' | 'low' | 'manual';

const isManual = computed(() => props.extractStatus === 'FAILED');

/** E-7：无档位且非「已转人工」时整个徽章不渲染 */
const visible = computed(() => isManual.value || props.level != null);

const tone = computed<Tone>(() => {
  if (isManual.value) return 'manual';
  if (props.level === 'HIGH') return 'high';
  if (props.level === 'MEDIUM') return 'medium';
  return 'low';
});

const label = computed(() => {
  if (tone.value === 'manual') return '已转人工';
  if (tone.value === 'high') return '高置信';
  return '待复核';
});

const defaultReason: Record<Tone, string> = {
  high: '字段完整且需求要点可在邮件原文中回溯，无需人工复核。',
  medium: '抽取经过重试或存在 1 个关键字段缺失，建议人工看一眼。',
  low: '存在 2 个以上关键字段缺失，或需求要点原文可回溯率偏低（疑似幻觉），必须复核。',
  manual: '邮件解析失败，已转人工处理；确认后可手工补录线索。',
};

const tip = computed(() => props.reason?.trim() || defaultReason[tone.value]);
</script>

<template>
  <el-tooltip v-if="visible" :content="tip" placement="top">
    <span class="confidence-badge" :class="`is-${tone}`">
      <el-icon v-if="tone === 'medium' || tone === 'low' || tone === 'manual'" :size="12"><Warning /></el-icon>
      <span>{{ label }}</span>
    </span>
  </el-tooltip>
</template>

<style scoped>
.confidence-badge {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
  height: 20px;
  padding: 0 6px;
  border-radius: var(--radius-sm);
  border: 1px solid transparent;
  font-size: var(--text-xs);
  font-weight: var(--fw-medium);
  line-height: 1;
  white-space: nowrap;
}

/* 高置信：默认色，无额外标记 */
.confidence-badge.is-high {
  color: var(--success-text);
  background: var(--success-subtle);
  border-color: var(--success-border);
}

/* 中置信：字段旁警示图标 + 「待复核」文字 */
.confidence-badge.is-medium {
  color: var(--warning-text);
  background: transparent;
  border-color: transparent;
}

/* 低置信：整字段底色 + 边框，比中置信更强调（疑似幻觉风险更高） */
.confidence-badge.is-low {
  color: var(--warning-text);
  background: var(--warning-subtle);
  border-color: var(--warning-border);
}

/* 已转人工：中性强调，表示流程已移交而非告警 */
.confidence-badge.is-manual {
  color: var(--meta);
  background: var(--surface-3);
  border-color: var(--border);
}
</style>
