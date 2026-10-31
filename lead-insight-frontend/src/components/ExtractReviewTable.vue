<script setup lang="ts">
/**
 * ExtractReviewTable —— 单条粘贴导入的字段确认表（UIUX §7.5.4）。
 * 三列：左字段名 / 中抽取值（可就地修正）/ 右置信度徽章。
 *
 * 契约缺口说明：openapi 1.2.2 未提供字段级置信度，右列徽章暂以整单档位标注
 * （ConfidenceBadge 复用，不另造组件）；「待复核」行级高亮由 missing（未抽取到）驱动。
 */
import ConfidenceBadge from '@/components/ConfidenceBadge.vue';
import type { ConfidenceLevel, ExtractStatus } from '@/types/lead';
import type { ExtractDraftField, ExtractFieldOption } from '@/types/import';

const props = defineProps<{
  fields: ExtractDraftField[];
  /** 整单抽取置信度档位（openapi 仅提供整单） */
  level: ConfidenceLevel | null;
  extractStatus: ExtractStatus | null;
  /** 转人工录入模式：无抽取结果，右列不显示置信度徽章 */
  manual: boolean;
  intentOptions: ExtractFieldOption[];
  scaleOptions: ExtractFieldOption[];
  /** 提交被拦下的字段 key 集合（必填未填），用于错误描边 */
  invalidKeys: string[];
}>();

const emit = defineEmits<{ change: [key: string, value: string | number | null | undefined] }>();

function onChange(key: string, value: string | number | null | undefined): void {
  emit('change', key, value);
}

function isInvalid(field: ExtractDraftField): boolean {
  return props.invalidKeys.includes(field.key);
}
</script>

<template>
  <div class="review">
    <div class="review__head review-row">
      <span class="review-row__label">字段</span>
      <span class="review-row__control">抽取值（可修正）</span>
      <span class="review-row__confidence">置信度</span>
    </div>

    <div
      v-for="field in fields"
      :key="field.key"
      class="review-row"
      :class="{ 'is-pending': field.missing && !manual, 'is-invalid': isInvalid(field) }"
    >
      <span class="review-row__label">
        {{ field.label }}<em v-if="field.required" class="review-row__required">*</em>
      </span>

      <div class="review-row__control">
        <el-input
          v-if="field.kind === 'text'"
          :model-value="field.value ?? ''"
          :placeholder="field.placeholder"
          :maxlength="field.key === 'subjectTitle' ? 255 : field.key === 'customerName' ? 100 : 150"
          @update:model-value="onChange(field.key, $event)"
        />
        <el-input
          v-else-if="field.kind === 'textarea'"
          :model-value="field.value ?? ''"
          type="textarea"
          :rows="2"
          maxlength="500"
          show-word-limit
          :placeholder="field.placeholder"
          @update:model-value="onChange(field.key, $event)"
        />
        <el-input-number
          v-else-if="field.kind === 'number'"
          :model-value="field.value === null || field.value === '' ? undefined : Number(field.value)"
          :min="0"
          :step="1000"
          controls-position="right"
          placeholder="单位：元"
          class="review-row__number"
          @update:model-value="onChange(field.key, $event)"
        />
        <el-date-picker
          v-else-if="field.kind === 'date'"
          :model-value="field.value ?? ''"
          type="date"
          value-format="YYYY-MM-DD"
          placeholder="选择日期"
          class="review-row__date"
          @update:model-value="onChange(field.key, $event)"
        />
        <el-select
          v-else-if="field.kind === 'intent'"
          :model-value="field.value ?? ''"
          placeholder="请选择"
          clearable
          @update:model-value="onChange(field.key, $event)"
        >
          <el-option v-for="item in intentOptions" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
        <el-select
          v-else
          :model-value="field.value ?? ''"
          placeholder="请选择"
          clearable
          @update:model-value="onChange(field.key, $event)"
        >
          <el-option v-for="item in scaleOptions" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
      </div>

      <div class="review-row__confidence">
        <ConfidenceBadge v-if="!manual" :level="level" :extract-status="extractStatus" />
        <span v-else class="review__manual-mark">人工录入</span>
        <span v-if="field.missing && !manual" class="review__pending-mark">待复核</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.review {
  display: flex;
  flex-direction: column;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  overflow: hidden;
}

.review__head {
  background: var(--surface-2);
  font-size: var(--text-xs);
  color: var(--fg-3);
  letter-spacing: 0.02em;
}

.review-row {
  display: grid;
  grid-template-columns: 128px minmax(0, 1fr) 168px;
  gap: var(--space-3);
  align-items: center;
  padding: var(--space-2) var(--space-3);
  border-bottom: 1px solid var(--border-soft);
  transition: background-color var(--duration-fast) var(--ease-standard);
}

.review-row:last-child {
  border-bottom: none;
}

.review-row.is-pending {
  background: var(--warning-subtle);
}

.review-row.is-invalid .review-row__control :deep(.el-input__wrapper),
.review-row.is-invalid .review-row__control :deep(.el-textarea__inner) {
  box-shadow: 0 0 0 1px var(--danger) inset;
}

.review-row__label {
  font-size: var(--text-sm);
  color: var(--fg-2);
}

.review-row__required {
  color: var(--danger);
  font-style: normal;
  margin-left: 2px;
}

.review-row__control {
  min-width: 0;
}

.review-row__number,
.review-row__date {
  width: 100%;
}

.review-row__confidence {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  flex-wrap: wrap;
}

.review__manual-mark,
.review__pending-mark {
  font-size: var(--text-xs);
  line-height: 1;
  padding: 2px 6px;
  border-radius: var(--radius-sm);
  white-space: nowrap;
}

.review__manual-mark {
  color: var(--neutral-text);
  background: var(--neutral-subtle);
  border: 1px solid var(--neutral-border);
}

.review__pending-mark {
  color: var(--warning-text);
  background: transparent;
  border: 1px solid var(--warning-border);
}
</style>
