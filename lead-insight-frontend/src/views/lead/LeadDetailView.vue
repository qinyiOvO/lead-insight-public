<script setup lang="ts">
/**
 * /leads/:id 线索详情（T1-13）
 * 区块：头部摘要 / 基础信息 / 抽取结果 / 评分依据 + 评分明细 / 跟进记录 / 邮件原文 + 元信息。
 * - 状态推进只渲染合法目标状态；终态 WON/INVALID 不渲染任何推进按钮（AC-25）
 * - 42201 非法迁移用后端原始 message 提示并刷新详情
 * - 认领仅在 status=NEW 且 ownerId 为空时出现
 * - 邮件原文用等宽字体 + pre-wrap 纯文本渲染（禁止 v-html）
 */
import { computed, onMounted, reactive, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { ArrowLeft, Refresh } from '@element-plus/icons-vue';
import { changeLeadStatus, claimLead, createFollowUp, getLead, getLeadScores, listFollowUps } from '@/api/lead';
import { toBizMessage } from '@/api/error';
import type { FollowUpType, FollowUpVO, LeadDetailVO, LeadStatus, ScoreDetailVO } from '@/types/lead';
import { useUserStore } from '@/stores/user';
import {
  COMPANY_SCALE_LABEL,
  EXTRACT_STATUS_LABEL,
  FOLLOW_TYPE_LABEL,
  IMPORT_SOURCE_LABEL,
  formatAmount,
  formatDate,
  formatDateTime,
  orDash,
} from '@/utils/format';
import CategoryTag from '@/components/CategoryTag.vue';
import ConfidenceBadge from '@/components/ConfidenceBadge.vue';
import PriorityTag from '@/components/PriorityTag.vue';
import StatusDot from '@/components/StatusDot.vue';

const route = useRoute();
const router = useRouter();
const userStore = useUserStore();

const leadId = Number(route.params.id);
const detail = ref<LeadDetailVO | null>(null);
const scores = ref<ScoreDetailVO[]>([]);
const followUps = ref<FollowUpVO[]>([]);
const loading = ref(true);
const error = ref(false);
const statusSubmitting = ref(false);
const claiming = ref(false);
const followSubmitting = ref(false);

const followForm = reactive({ followType: 'NOTE' as FollowUpType, content: '' });

const followTypeOptions = Object.keys(FOLLOW_TYPE_LABEL).map((value) => ({
  value: value as FollowUpType,
  label: FOLLOW_TYPE_LABEL[value] as string,
}));

const isSpecialist = computed(() => userStore.isSpecialist);
const canClaim = computed(
  () => isSpecialist.value && detail.value?.status === 'NEW' && detail.value?.ownerId === null,
);

/** 只渲染合法目标状态；终态返回空数组（AC-25） */
const statusActions = computed<{ to: LeadStatus; label: string; kind: 'primary' | 'success' | '' }[]>(() => {
  const status = detail.value?.status;
  if (status === 'NEW') {
    return [
      { to: 'FOLLOWING', label: '开始跟进', kind: 'primary' },
      { to: 'INVALID', label: '判为无效', kind: '' },
    ];
  }
  if (status === 'FOLLOWING') {
    return [
      { to: 'WON', label: '成交', kind: 'success' },
      { to: 'INVALID', label: '判为无效', kind: '' },
    ];
  }
  return [];
});

/** 详情页主标题：客户名（空则公司名，都空则 线索 #{id}）——用户靠客户/公司名认人，不靠编号 */
const headTitle = computed(() => {
  const name = detail.value?.customerName?.trim();
  const company = detail.value?.companyName?.trim();
  return name || company || `线索 #${detail.value?.id ?? ''}`;
});

/** 负责人：openapi 1.2.1 起由 ownerName 提供；未认领是有业务含义的状态，不显示为缺数据 */
const ownerText = computed(() => detail.value?.ownerName?.trim() || '未认领');

const scoreTotal = computed(() => scores.value.reduce((sum, item) => sum + (item.itemScore ?? 0), 0));
const scoreMax = computed(() => scores.value.reduce((sum, item) => sum + (item.maxScore ?? 0), 0));

const scaleText = computed(() => {
  const scale = detail.value?.companyScale as keyof typeof COMPANY_SCALE_LABEL | null | undefined;
  return scale ? COMPANY_SCALE_LABEL[scale] : '—';
});

const extractStatusText = computed(() =>
  detail.value ? EXTRACT_STATUS_LABEL[detail.value.extractStatus] : '—',
);

const importSourceText = computed(() =>
  detail.value ? (IMPORT_SOURCE_LABEL[detail.value.importSource] ?? detail.value.importSource) : '—',
);

async function loadFollowUps(): Promise<void> {
  if (!Number.isFinite(leadId)) return;
  try {
    const list = await listFollowUps(leadId);
    followUps.value = list
      .slice()
      .sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime());
  } catch {
    followUps.value = [];
  }
}

async function loadDetail(): Promise<void> {
  if (!Number.isFinite(leadId)) {
    loading.value = false;
    error.value = true;
    return;
  }
  loading.value = true;
  error.value = false;
  try {
    const data = await getLead(leadId);
    detail.value = data;
    if (Array.isArray(data.scores) && data.scores.length > 0) {
      scores.value = data.scores;
    } else {
      try {
        scores.value = await getLeadScores(leadId);
      } catch {
        scores.value = [];
      }
    }
    await loadFollowUps();
  } catch {
    error.value = true;
  } finally {
    loading.value = false;
  }
}

async function handleClaim(): Promise<void> {
  claiming.value = true;
  try {
    await claimLead(leadId);
    ElMessage.success('已认领，状态更新为「跟进中」');
    await loadDetail();
  } catch {
    // 拦截器已提示
  } finally {
    claiming.value = false;
  }
}

async function handleStatus(to: LeadStatus): Promise<void> {
  statusSubmitting.value = true;
  try {
    // silent：由本页展示后端原始 message（含 42201 非法迁移原因）
    await changeLeadStatus(leadId, { toStatus: to }, { silent: true });
    ElMessage.success('状态已更新');
    await loadDetail();
  } catch (err) {
    ElMessage.error(toBizMessage(err).message);
    await loadDetail();
  } finally {
    statusSubmitting.value = false;
  }
}

async function submitFollowUp(): Promise<void> {
  const content = followForm.content.trim();
  if (!content) {
    ElMessage.warning('请填写跟进内容');
    return;
  }
  followSubmitting.value = true;
  try {
    await createFollowUp(leadId, { followType: followForm.followType, content });
    ElMessage.success('跟进记录已保存');
    followForm.content = '';
    followForm.followType = 'NOTE';
    await loadFollowUps();
  } catch {
    // 拦截器已提示
  } finally {
    followSubmitting.value = false;
  }
}

function backToList(): void {
  void router.push('/leads');
}

onMounted(loadDetail);
</script>

<template>
  <div class="lead-detail">
    <button class="back-link" type="button" @click="backToList">
      <el-icon :size="16"><ArrowLeft /></el-icon>
      <span>返回线索列表</span>
    </button>

    <el-skeleton v-if="loading && !detail" :rows="8" animated />

    <div v-else-if="error && !detail" class="load-error">
      <p class="load-error__text">线索详情加载失败，可能已被删除或后端不可用。</p>
      <div class="load-error__actions">
        <el-button type="primary" :icon="Refresh" @click="loadDetail">重试</el-button>
        <el-button @click="backToList">返回线索列表</el-button>
      </div>
    </div>

    <template v-else-if="detail">
      <header class="detail-head">
        <div class="detail-head__main">
          <h1 class="detail-head__title">{{ headTitle }}</h1>
          <div class="detail-head__tags">
            <span class="detail-head__no">#{{ detail.id }}</span>
            <CategoryTag :category="detail.intentCategory" />
            <PriorityTag :level="detail.priorityLevel" :score="detail.priorityScore" />
            <StatusDot :status="detail.status" />
          </div>
          <p class="detail-head__meta">
            客户：{{ orDash(detail.customerName) }} · 联系方式：{{ orDash(detail.contactInfo) }} · 负责人：{{ ownerText }}
          </p>
        </div>

        <div class="detail-head__actions">
          <el-button v-if="canClaim" type="primary" :loading="claiming" @click="handleClaim">认领线索</el-button>
          <el-button
            v-for="action in statusActions"
            :key="action.to"
            :type="action.kind"
            :loading="statusSubmitting"
            @click="handleStatus(action.to)"
          >
            {{ action.label }}
          </el-button>
        </div>
      </header>

      <div class="detail-grid">
        <div class="detail-col">
          <section class="panel">
            <h2 class="section-title">基础信息</h2>
            <dl class="field-list">
              <div class="field"><dt>客户名称</dt><dd>{{ orDash(detail.customerName) }}</dd></div>
              <div class="field"><dt>所属公司</dt><dd>{{ orDash(detail.companyName) }}</dd></div>
              <div class="field"><dt>联系方式</dt><dd>{{ orDash(detail.contactInfo) }}</dd></div>
              <div class="field"><dt>意图类别</dt><dd><CategoryTag :category="detail.intentCategory" /></dd></div>
              <div class="field"><dt>预算金额</dt><dd>{{ detail.budgetText || formatAmount(detail.budgetAmount) }}</dd></div>
              <div class="field"><dt>期望时间</dt><dd>{{ detail.expectedTimeText || formatDate(detail.expectedTime) }}</dd></div>
              <div class="field"><dt>公司规模</dt><dd>{{ scaleText }}</dd></div>
              <div class="field"><dt>需求要点</dt><dd>{{ orDash(detail.demandSummary) }}</dd></div>
            </dl>
          </section>

          <section class="panel">
            <h2 class="section-title">抽取结果</h2>
            <dl class="field-list">
              <div class="field"><dt>抽取状态</dt><dd>{{ extractStatusText }}</dd></div>
              <div class="field">
                <dt>置信度</dt>
                <dd class="field__confidence">
                  <ConfidenceBadge :level="detail.confidenceLevel" :extract-status="detail.extractStatus" />
                  <span class="field__confidence-value">
                    {{ detail.extractConfidence === null ? '未评估' : detail.extractConfidence.toFixed(2) }}
                  </span>
                </dd>
              </div>
              <div class="field"><dt>命中关键词</dt><dd>{{ orDash(detail.keywordsHit) }}</dd></div>
              <div class="field"><dt>邮件主题</dt><dd>{{ orDash(detail.subjectTitle) }}</dd></div>
            </dl>
          </section>

          <section class="panel">
            <h2 class="section-title">评分依据</h2>
            <p class="score-reason">{{ orDash(detail.scoreReason) }}</p>

            <table class="score-table">
              <thead>
                <tr>
                  <th scope="col">规则</th>
                  <th scope="col">原始取值</th>
                  <th scope="col" class="num">得分</th>
                  <th scope="col" class="num">上限</th>
                  <th scope="col">说明</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="item in scores" :key="item.ruleCode">
                  <td>{{ item.ruleName }}</td>
                  <td>{{ orDash(item.rawValue) }}</td>
                  <td class="num">{{ item.itemScore }}</td>
                  <td class="num">{{ item.maxScore }}</td>
                  <td>{{ orDash(item.reason) }}</td>
                </tr>
                <tr v-if="scores.length === 0">
                  <td colspan="5" class="score-table__empty">暂无评分明细</td>
                </tr>
              </tbody>
              <tfoot v-if="scores.length > 0">
                <tr>
                  <td colspan="2">合计</td>
                  <td class="num">{{ scoreTotal }}</td>
                  <td class="num">{{ scoreMax }}</td>
                  <td>优先级 {{ detail.priorityScore }} 分 · {{ detail.priorityLevel }}</td>
                </tr>
              </tfoot>
            </table>
          </section>

          <section class="panel">
            <h2 class="section-title">跟进记录</h2>

            <div class="follow-form">
              <el-select v-model="followForm.followType" class="follow-form__type">
                <el-option v-for="item in followTypeOptions" :key="item.value" :label="item.label" :value="item.value" />
              </el-select>
              <el-input
                v-model="followForm.content"
                type="textarea"
                :rows="2"
                maxlength="1000"
                placeholder="记录这次跟进的关键结论，例如：已电话联系，客户确认需要报价单。"
              />
              <el-button
                type="primary"
                :loading="followSubmitting"
                :disabled="!isSpecialist"
                @click="submitFollowUp"
              >
                添加跟进记录
              </el-button>
            </div>

            <ul v-if="followUps.length > 0" class="timeline">
              <li v-for="item in followUps" :key="item.id" class="timeline__item">
                <span class="timeline__dot" aria-hidden="true" />
                <div class="timeline__body">
                  <div class="timeline__head">
                    <span class="timeline__type">{{ FOLLOW_TYPE_LABEL[item.followType] ?? item.followType }}</span>
                    <span class="timeline__time">{{ formatDateTime(item.createdAt) }}</span>
                  </div>
                  <p class="timeline__content">{{ item.content }}</p>
                </div>
              </li>
            </ul>
            <p v-else class="follow-empty">还没有跟进记录，添加第一条即可形成完整留痕。</p>
          </section>
        </div>

        <aside class="detail-side">
          <section class="panel">
            <h2 class="section-title">邮件原文</h2>
            <pre class="text-pre mail">{{ detail.originalText }}</pre>
          </section>

          <section class="panel">
            <h2 class="section-title">元信息</h2>
            <dl class="field-list">
              <div class="field">
                <dt>录入方式</dt>
                <dd>
                  {{ importSourceText }}
                  <!-- J-4：手工录入 extractStatus 恒 WAITING（契约明文设计），"待抽取/识别中"语义误导，文案澄清 -->
                  <span v-if="detail.extractStatus === 'WAITING'" class="meta-note">手工录入不触发 AI 识别</span>
                </dd>
              </div>
              <div class="field"><dt>创建时间</dt><dd>{{ formatDateTime(detail.createdAt) }}</dd></div>
              <div class="field"><dt>抽取状态</dt><dd>{{ extractStatusText }}</dd></div>
            </dl>
          </section>
        </aside>
      </div>
    </template>
  </div>
</template>

<style scoped>
.back-link {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
  margin-bottom: var(--space-4);
  padding: 0;
  border: none;
  background: transparent;
  color: var(--fg-2);
  font-size: var(--text-base);
  cursor: pointer;
}

.back-link:hover {
  color: var(--primary);
}

.load-error {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-3);
  padding: var(--space-12) 0;
}

.load-error__text {
  font-size: var(--text-base);
  color: var(--fg-2);
}

.load-error__actions {
  display: flex;
  gap: var(--space-2);
}

.detail-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-4);
  padding-bottom: var(--space-4);
  border-bottom: 1px solid var(--divider);
}

.detail-head__title {
  font-size: var(--text-xl);
  font-weight: var(--fw-semibold);
  letter-spacing: -0.01em;
  color: var(--fg);
}

.detail-head__tags {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  margin-top: var(--space-2);
}

.detail-head__no {
  font-family: var(--font-mono);
  font-size: var(--text-sm);
  color: var(--fg-3);
}

.detail-head__meta {
  margin-top: var(--space-2);
  font-size: var(--text-sm);
  color: var(--fg-3);
}

.detail-head__actions {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  flex: none;
}

.detail-grid {
  display: grid;
  grid-template-columns: 2fr 1fr;
  gap: var(--space-6);
  margin-top: var(--space-5);
}

.detail-col,
.detail-side {
  display: flex;
  flex-direction: column;
  gap: var(--space-5);
  min-width: 0;
}

.panel {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  padding: var(--space-4);
}

.panel .section-title {
  margin-bottom: var(--space-3);
}

.field-list {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--space-3) var(--space-6);
  margin: 0;
}

.detail-side .field-list {
  grid-template-columns: minmax(0, 1fr);
}

.field {
  display: flex;
  align-items: baseline;
  gap: var(--space-2);
  min-width: 0;
}

.field dt {
  flex: none;
  width: 72px;
  font-size: var(--text-sm);
  color: var(--fg-3);
}

.field dd {
  margin: 0;
  min-width: 0;
  font-size: var(--text-base);
  color: var(--fg);
  word-break: break-word;
}

/* J-4：手工录入澄清说明（弱化小字，随 WAITING 态出现） */
.meta-note {
  display: inline-block;
  margin-left: var(--space-2);
  font-size: var(--text-xs);
  color: var(--fg-3);
}

.field__confidence {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.field__confidence-value {
  font-family: var(--font-mono);
  font-size: var(--text-sm);
  color: var(--fg-3);
}

.score-reason {
  padding: var(--space-3);
  margin-bottom: var(--space-3);
  background: var(--surface-3);
  border-radius: var(--radius-sm);
  font-size: var(--text-base);
  color: var(--fg-2);
  line-height: var(--leading-relaxed);
}

.score-table {
  width: 100%;
  border-collapse: collapse;
  font-size: var(--text-sm);
}

.score-table th,
.score-table td {
  padding: var(--space-2) var(--space-3);
  border-bottom: 1px solid var(--border-soft);
  text-align: left;
  vertical-align: top;
}

.score-table th {
  color: var(--fg-3);
  font-weight: var(--fw-medium);
  background: var(--surface-2);
}

.score-table td {
  color: var(--fg-2);
}

.score-table .num {
  font-family: var(--font-mono);
  font-variant-numeric: tabular-nums;
  text-align: right;
}

.score-table tfoot td {
  font-weight: var(--fw-medium);
  color: var(--fg);
  background: var(--surface-2);
}

.score-table__empty {
  text-align: center;
  color: var(--meta);
}

.follow-form {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  margin-bottom: var(--space-4);
}

.follow-form__type {
  width: 132px;
}

.timeline {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

.timeline__item {
  display: flex;
  gap: var(--space-3);
}

.timeline__dot {
  flex: none;
  width: 8px;
  height: 8px;
  margin-top: 6px;
  border-radius: var(--radius-full);
  background: var(--primary);
}

.timeline__body {
  min-width: 0;
  padding-bottom: var(--space-3);
  border-bottom: 1px solid var(--border-soft);
  flex: 1;
}

.timeline__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-2);
}

.timeline__type {
  font-size: var(--text-sm);
  font-weight: var(--fw-medium);
  color: var(--fg);
}

.timeline__time {
  font-family: var(--font-mono);
  font-size: var(--text-xs);
  color: var(--fg-3);
}

.timeline__content {
  margin-top: var(--space-1);
  font-size: var(--text-base);
  color: var(--fg-2);
  line-height: var(--leading-relaxed);
  white-space: pre-wrap;
  word-break: break-word;
}

.follow-empty {
  font-size: var(--text-sm);
  color: var(--fg-3);
}

.mail {
  max-height: 400px;
  overflow-y: auto;
  padding: var(--space-3);
  background: var(--surface-3);
  border-radius: var(--radius-sm);
  margin: 0;
}
</style>
