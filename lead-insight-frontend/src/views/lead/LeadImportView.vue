<script setup lang="ts">
/**
 * /leads/import 单条粘贴导入（T2-05，UIUX §7.5.4 五态）。
 *
 * 状态机：idle 默认 -> loading（模型解析中）-> review 确认表（partial：缺失字段标「待复核」，可修正）
 *        -> saving -> success（跳详情）；识别失败 -> error（重试 / 转人工录入）。
 *
 * 契约现状（openapi 1.2.2）：无「识别 -> 确认 -> 保存」两步预览端点，POST /leads 粘贴模式
 * 在识别时就建线索，故本页「保存为线索」= PATCH /leads/{id} 回填人工修正字段。
 * 若契约后续补充预览端点，仅需替换 startExtract 内的服务调用。
 */
import { computed, onUnmounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { MagicStick } from '@element-plus/icons-vue';
import { createLead, getLead, reextractLead, updateLead } from '@/api/lead';
import { BizError } from '@/api/error';
import { COMPANY_SCALE_LABEL, EXTRACT_STATUS_LABEL, INTENT_LABEL } from '@/utils/format';
import type { CompanyScale, ConfidenceLevel, ExtractStatus, IntentCategory, LeadDetailVO, LeadUpdateRequest } from '@/types/lead';
import type { ExtractDraftField, ExtractFieldOption } from '@/types/import';
import ExtractReviewTable from '@/components/ExtractReviewTable.vue';

const router = useRouter();

type Phase = 'idle' | 'loading' | 'review' | 'error' | 'saving';

const EXAMPLE_TEXT =
  '客户：广州振华五金制品有限公司 · 我方计划采购 304 不锈钢管 500 米，采购预算 60 万元，希望 7 天内拿到正式报价并签订合同。';

const intentOptions: ExtractFieldOption[] = (Object.keys(INTENT_LABEL) as IntentCategory[]).map((value) => ({
  value,
  label: INTENT_LABEL[value],
}));
const scaleOptions: ExtractFieldOption[] = (Object.keys(COMPANY_SCALE_LABEL) as CompanyScale[]).map((value) => ({
  value,
  label: COMPANY_SCALE_LABEL[value],
}));

const phase = ref<Phase>('idle');
const originalText = ref('');
const fields = ref<ExtractDraftField[]>([]);
const createdId = ref<number | null>(null);
const level = ref<ConfidenceLevel | null>(null);
const extractStatus = ref<ExtractStatus | null>(null);
const manual = ref(false);
const errorText = ref('');
const invalidKeys = ref<string[]>([]);
// review 分支模板里 phase 已被窄化为 'review'，直接比 'saving' 会报 TS2367，故用独立计算属性
const saving = computed(() => phase.value === 'saving');

const isEmptyValue = (value: string | number | null): boolean => value === null || value === undefined || String(value).trim() === '';

function makeFields(): ExtractDraftField[] {
  return [
    { key: 'subjectTitle', label: '邮件主题', kind: 'text', value: '', missing: true, placeholder: '如：304 不锈钢管采购询价' },
    { key: 'customerName', label: '客户名称', kind: 'text', value: '', missing: true, required: true, placeholder: '如：陈先生' },
    { key: 'companyName', label: '所属公司', kind: 'text', value: '', missing: true, placeholder: '如：广州振华五金制品有限公司' },
    { key: 'contactInfo', label: '联系方式', kind: 'text', value: '', missing: true, placeholder: '电话 / 邮箱' },
    { key: 'intentCategory', label: '意图类别', kind: 'intent', value: '', missing: true },
    { key: 'demandSummary', label: '需求要点', kind: 'textarea', value: '', missing: true, required: true, placeholder: '如：询 304 不锈钢管件报价，需 500 米，月底前要货' },
    { key: 'budgetAmount', label: '预算金额（元）', kind: 'number', value: null, missing: true },
    { key: 'expectedTime', label: '期望时间', kind: 'date', value: '', missing: true },
    { key: 'companyScale', label: '公司规模', kind: 'scale', value: '', missing: true },
  ];
}

fields.value = makeFields();

function prefill(lead: LeadDetailVO, options?: { onlyMissing?: boolean }): void {
  const byKey: Record<string, string | number | null> = {
    subjectTitle: lead.subjectTitle,
    customerName: lead.customerName,
    companyName: lead.companyName,
    contactInfo: lead.contactInfo,
    intentCategory: lead.intentCategory ?? '',
    demandSummary: lead.demandSummary,
    budgetAmount: lead.budgetAmount,
    expectedTime: lead.expectedTime ?? '',
    companyScale: lead.companyScale ?? '',
  };
  fields.value = makeFields().map((field) => {
    const raw = byKey[field.key] ?? (field.kind === 'number' ? null : '');
    // onlyMissing：异步回填时不覆盖用户已人工补全的字段
    const keep = options?.onlyMissing && !isEmptyValue(field.value) ? field.value : raw;
    return { ...field, value: keep, missing: isEmptyValue(keep) };
  });
}

function onChange(key: string, value: string | number | null | undefined): void {
  const field = fields.value.find((item) => item.key === key);
  if (!field) return;
  // el-input-number 清空时发 undefined，统一归一为 null（字段模型不含 undefined）
  field.value = value ?? null;
  field.missing = isEmptyValue(value ?? null);
  invalidKeys.value = invalidKeys.value.filter((item) => item !== key);
}

/** 识别：粘贴模式只带 originalText（带任一手工字段会被后端判为人工录入，不调模型） */
async function startExtract(): Promise<void> {
  if (!originalText.value.trim()) {
    // 行 14 之①（R2 判据表 v1.1 / AC-33 前端层）：逐字文案，本地拦截不发请求
    errorText.value = '请粘贴有效邮件内容';
    phase.value = 'error';
    return;
  }
  phase.value = 'loading';
  errorText.value = '';
  manual.value = false;
  invalidKeys.value = [];
  try {
    const lead = await createLead({ originalText: originalText.value.trim() });
    createdId.value = lead.id;
    // LeadVO 不含 contactInfo / budgetAmount / companyScale，取详情补全确认表
    const detail = await getLead(lead.id);
    level.value = detail.confidenceLevel;
    extractStatus.value = detail.extractStatus;
    prefill(detail);
    if (detail.extractStatus === 'FAILED') {
      // FAILED＝解析重试穷尽转人工，走 error 态逐字文案（与 DEGRADED 严格区分）
      errorText.value = '多次解析尝试均失败，本条已转人工。';
      phase.value = 'error';
      return;
    }
    phase.value = 'review';
    // 后端抽取为异步：WAITING/PROCESSING 时轮询详情，落定后回填缺失字段
    if (extractStatus.value === 'WAITING' || extractStatus.value === 'PROCESSING') startBackfillPolling();
  } catch (err) {
    errorText.value = err instanceof BizError ? err.message : '解析失败，请稍后重试';
    phase.value = 'error';
  }
}

// ---- 异步抽取结果回填轮询（2s 间隔，上限 90s；不覆盖已人工补全的字段）----
let pollTimer: number | null = null;

function stopBackfillPolling(): void {
  if (pollTimer !== null) {
    window.clearTimeout(pollTimer);
    pollTimer = null;
  }
}

function startBackfillPolling(): void {
  stopBackfillPolling();
  const startedAt = Date.now();
  const tick = async (): Promise<void> => {
    pollTimer = null;
    if (createdId.value === null || phase.value !== 'review' || manual.value) return;
    try {
      const detail = await getLead(createdId.value);
      if (detail.extractStatus === 'WAITING' || detail.extractStatus === 'PROCESSING') {
        if (Date.now() - startedAt < 90_000) pollTimer = window.setTimeout(tick, 2000);
        return; // 仍在抽取：保持「待抽取」提示，用户可先人工补全
      }
      level.value = detail.confidenceLevel;
      extractStatus.value = detail.extractStatus;
      prefill(detail, { onlyMissing: true });
      if (detail.extractStatus === 'FAILED') {
        errorText.value = '多次解析尝试均失败，本条已转人工。';
        phase.value = 'error';
      }
    } catch {
      // 单次查询失败不中断轮询
      if (Date.now() - startedAt < 90_000) pollTimer = window.setTimeout(tick, 2000);
    }
  };
  pollTimer = window.setTimeout(tick, 2000);
}

onUnmounted(stopBackfillPolling);

/** 转人工录入：清空抽取值，就地人工补全后保存 */
function toManual(): void {
  if (createdId.value === null) return;
  stopBackfillPolling();
  manual.value = true;
  level.value = null;
  extractStatus.value = null;
  fields.value = fields.value.map((field) => ({ ...field, value: field.kind === 'number' ? null : '', missing: true }));
  phase.value = 'review';
}

/**
 * 重新识别：已有草稿线索时走 POST /leads/{id}/extract 重抽取（避免同文案重建触发 40901）；
 * 尚未创建线索（如空文本直接失败）时回粘贴框重新开始。
 */
async function reextract(): Promise<void> {
  if (createdId.value === null) {
    phase.value = 'idle';
    void startExtract();
    return;
  }
  stopBackfillPolling();
  phase.value = 'loading';
  errorText.value = '';
  try {
    await reextractLead(createdId.value);
    const detail = await getLead(createdId.value);
    level.value = detail.confidenceLevel;
    extractStatus.value = detail.extractStatus;
    prefill(detail);
    phase.value = 'review';
    if (extractStatus.value === 'WAITING' || extractStatus.value === 'PROCESSING') startBackfillPolling();
  } catch (err) {
    errorText.value = err instanceof BizError ? err.message : '解析失败，请稍后重试';
    phase.value = 'error';
  }
}

function retry(): void {
  void reextract();
}

const missingRequired = computed(() =>
  fields.value.filter((field) => field.required && isEmptyValue(field.value)).map((field) => field.key),
);

async function save(): Promise<void> {
  if (createdId.value === null) return;
  const pending = missingRequired.value;
  if (pending.length > 0) {
    invalidKeys.value = pending;
    ElMessage.warning('客户名称与需求要点为必填，请补全后保存');
    return;
  }
  const byKey = new Map(fields.value.map((field) => [field.key, field.value]));
  const payload: LeadUpdateRequest = {
    customerName: String(byKey.get('customerName') ?? '').trim() || undefined,
    companyName: String(byKey.get('companyName') ?? '').trim() || undefined,
    contactInfo: String(byKey.get('contactInfo') ?? '').trim() || undefined,
    demandSummary: String(byKey.get('demandSummary') ?? '').trim() || undefined,
    budgetAmount: byKey.get('budgetAmount') === null || byKey.get('budgetAmount') === '' ? undefined : Number(byKey.get('budgetAmount')),
    expectedTime: String(byKey.get('expectedTime') ?? '').trim() || undefined,
  };
  const intent = String(byKey.get('intentCategory') ?? '');
  const scale = String(byKey.get('companyScale') ?? '');
  if (intent) payload.intentCategory = intent as IntentCategory;
  if (scale) payload.companyScale = scale as CompanyScale;

  phase.value = 'saving';
  try {
    await updateLead(createdId.value, payload);
    ElMessage.success('线索已保存，优先级已按修正后的字段重算');
    await router.replace(`/leads/${createdId.value}`);
  } catch {
    phase.value = 'review';
  }
}

/** DEGRADED 独立降级态（UIUX §7.5.4 H-2 逐字文案，warning 非 danger） */
const degradedPanel = computed(() => phase.value === 'review' && !manual.value && extractStatus.value === 'DEGRADED');

const statusAlert = computed(() => {
  if (manual.value || phase.value !== 'review') return null;
  if (extractStatus.value === 'WAITING') {
    return { type: 'info' as const, title: '待抽取：系统已受理本次识别，抽取结果稍后回填，可直接人工补全后保存。' };
  }
  return null;
});
</script>

<template>
  <div class="import-page">
    <header class="page-head">
      <h1 class="page-head__title">单条粘贴导入</h1>
      <p class="page-head__desc">
        粘贴一封客户邮件原文，系统自动抽取结构化字段并给出整体置信度；缺失或低置信字段请就地修正后保存。
      </p>
    </header>

    <!-- 态1 默认：粘贴框 + 开始识别 -->
    <section class="panel">
      <el-input
        v-model="originalText"
        type="textarea"
        :rows="9"
        :placeholder="EXAMPLE_TEXT"
        :disabled="phase === 'loading' || phase === 'saving'"
      />
      <div class="import-actions">
        <el-button
          type="primary"
          :icon="MagicStick"
          :loading="phase === 'loading'"
          :disabled="phase === 'saving'"
          @click="startExtract"
        >
          开始识别
        </el-button>
        <span class="import-actions__hint">识别会创建线索草稿，确认字段后才算完成导入</span>
      </div>
    </section>

    <!-- 态2 loading：模型解析中骨架 -->
    <section v-if="phase === 'loading'" class="panel">
      <p class="loading-title">模型解析中，正在抽取客户与需求要点…</p>
      <el-skeleton :rows="5" animated />
    </section>

    <!-- 态4 error/FAILED：解析失败 + 重试 / 转人工录入（UIUX §7.5.4 逐字文案，danger） -->
    <section v-if="phase === 'error'" class="panel import-error">
      <p class="import-error__title">解析失败，点击重试 / 转人工录入</p>
      <p class="import-error__detail">{{ errorText }}</p>
      <div class="import-error__actions">
        <el-button type="primary" @click="retry">重新识别</el-button>
        <el-button v-if="createdId !== null" @click="toManual">转人工录入</el-button>
      </div>
    </section>

    <!-- 态 degraded/DEGRADED：模型不可用降级（H-2 定稿逐字文案，warning 非 danger，与 FAILED 严格区分） -->
    <section v-if="degradedPanel" class="panel import-degraded">
      <p class="import-degraded__title">AI 识别暂时不可用</p>
      <p class="import-degraded__desc">模型服务暂时不可用，录入功能不受影响。你的邮件文本仍保留在上方，可直接转人工录入，或稍后重试。</p>
      <div class="import-actions">
        <el-button type="primary" @click="toManual">转人工录入</el-button>
        <el-button :disabled="saving" @click="retry">重试</el-button>
      </div>
    </section>

    <!-- 态3 partial / 抽取结果确认表 -->
    <section v-if="phase === 'review' && !degradedPanel" class="panel">
      <el-alert v-if="statusAlert" :type="statusAlert.type" :title="statusAlert.title" :closable="false" show-icon class="import-alert" />

      <div class="review-head">
        <h2 class="review-head__title">抽取结果确认</h2>
        <span v-if="!manual" class="review-head__meta">
          抽取状态：{{ EXTRACT_STATUS_LABEL[extractStatus ?? 'WAITING'] }}
        </span>
        <span v-else class="review-head__meta">人工录入模式：无抽取结果，保存后系统按补全字段重算优先级</span>
      </div>

      <ExtractReviewTable
        :fields="fields"
        :level="level"
        :extract-status="extractStatus"
        :manual="manual"
        :intent-options="intentOptions"
        :scale-options="scaleOptions"
        :invalid-keys="invalidKeys"
        @change="onChange"
      />

      <div class="import-actions">
        <el-button type="primary" :loading="saving" @click="save">保存为线索</el-button>
        <el-button :disabled="saving" @click="retry">重新识别</el-button>
        <el-button v-if="!manual" :disabled="saving" @click="toManual">转人工录入</el-button>
      </div>
    </section>
  </div>
</template>

<style scoped>
.page-head {
  margin-bottom: var(--space-4);
}

.page-head__title {
  font-size: var(--text-xl);
  font-weight: var(--fw-semibold);
  letter-spacing: -0.01em;
  color: var(--fg);
}

.page-head__desc {
  margin-top: var(--space-1);
  font-size: var(--text-base);
  color: var(--fg-3);
}

.panel {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  padding: var(--space-4);
}

.panel + .panel {
  margin-top: var(--space-4);
}

/* DEGRADED 降级态：语义色 warning（引用 --warning-text Token），非 danger */
.import-degraded__title {
  font-size: var(--text-lg);
  font-weight: var(--fw-semibold);
  color: var(--warning-text);
}

.import-degraded__desc {
  margin-top: var(--space-2);
  max-width: 65ch;
  color: var(--fg-2);
}

.import-actions {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  margin-top: var(--space-3);
}

.import-actions__hint {
  font-size: var(--text-sm);
  color: var(--meta);
}

.loading-title {
  margin-bottom: var(--space-3);
  font-size: var(--text-base);
  color: var(--fg-2);
}

.import-error__title {
  font-size: var(--text-base);
  color: var(--fg);
}

.import-error__detail {
  margin-top: var(--space-1);
  font-size: var(--text-sm);
  color: var(--danger-text);
}

.import-error__actions {
  display: flex;
  gap: var(--space-2);
  margin-top: var(--space-3);
}

.import-alert {
  margin-bottom: var(--space-3);
}

.review-head {
  display: flex;
  align-items: baseline;
  gap: var(--space-3);
  margin-bottom: var(--space-3);
}

.review-head__title {
  font-size: var(--text-md);
  font-weight: var(--fw-semibold);
  color: var(--fg);
}

.review-head__meta {
  font-size: var(--text-sm);
  color: var(--fg-3);
}
</style>
