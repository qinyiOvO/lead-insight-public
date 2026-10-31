<script setup lang="ts">
/**
 * CategoryTag —— 意图类别标签（el-tag 形态）。
 * 注意：对应 lead.intent_category 字段（内容属性）。
 * 与线索状态（StatusDot 圆点 + 文字）形态不同，同页并列不混淆。
 * 第五类文案为「无关」(IRRELEVANT)，线索状态第四态为「无效」，用词必须区分。
 */
import { computed } from 'vue';
import type { IntentCategory } from '@/types/lead';
import { INTENT_LABEL } from '@/utils/format';

const props = defineProps<{ category: IntentCategory | null }>();

/** 枚举 -> 令牌后缀（颜色全部取自 design-tokens.css） */
const TONE: Record<IntentCategory, string> = {
  CONSULT: 'inquiry',
  QUOTE: 'quote',
  COOPERATION: 'partner',
  COMPLAINT: 'complaint',
  IRRELEVANT: 'invalid',
};

const tone = computed(() => (props.category ? TONE[props.category] : 'invalid'));
const label = computed(() => (props.category ? INTENT_LABEL[props.category] : '未分类'));
</script>

<template>
  <el-tag class="category-tag" :class="`is-${tone}`" disable-transitions size="small" effect="light">
    {{ label }}
  </el-tag>
</template>

<style scoped>
.category-tag {
  height: 22px;
  padding: 0 6px;
  border-radius: var(--radius-sm);
  font-size: var(--text-xs);
  font-weight: var(--fw-medium);
}

.category-tag.is-inquiry {
  color: var(--cat-inquiry-text);
  background: var(--cat-inquiry-bg);
  border-color: var(--cat-inquiry);
}

.category-tag.is-quote {
  color: var(--cat-quote-text);
  background: var(--cat-quote-bg);
  border-color: var(--cat-quote);
}

.category-tag.is-partner {
  color: var(--cat-partner-text);
  background: var(--cat-partner-bg);
  border-color: var(--cat-partner);
}

.category-tag.is-complaint {
  color: var(--cat-complaint-text);
  background: var(--cat-complaint-bg);
  border-color: var(--cat-complaint);
}

.category-tag.is-invalid {
  color: var(--cat-invalid-text);
  background: var(--cat-invalid-bg);
  border-color: var(--cat-invalid);
}
</style>
