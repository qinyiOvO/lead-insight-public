<script setup lang="ts">
/**
 * /tasks/:id 批量导入任务进度（T3-04，UIUX §7.5.7 + §8.4）。
 * 2s 轮询 /import-tasks/{id}/progress，终态（SUCCESS/PARTIAL/FAILED）停；
 * 逐条结果 /items（openapi 1.3.1，含 PENDING/RUNNING 中间态，运行中条目不消失）。
 * 汇总口径（openapi 1.3.3，DEF-04）：成功 / 重复跳过 / 转人工 / 失败四列，
 * 成功+去重+转人工+失败 = 总数（AC-27 三类汇总 + 失败单列）。其中转人工（progress.manual）
 * 即 DEGRADED——线索已创建但 AI 识别降级转人工，语义 warning 而非失败红；
 * 失败（progress.fail）仅计 FAILED 条目（未产线索）与创建异常，DEGRADED 不计入。
 * 契约注记：§8.4 的「限流等待」任务级状态在 ImportTaskProgressVO.status（五态）中
 * 无承载，等后端补充枚举再渲染，前端不造状态；条目级「降级」已由 1.3.1 DEGRADED 承载。
 */
import { computed, onUnmounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { getImportTaskItems, getImportTaskProgress } from '@/api/importTask';
import type { ImportItemStatus, ImportTaskProgressVO } from '@/types/import-task';
import { IMPORT_ITEM_STATUS_LABEL } from '@/utils/format';
import TaskStatusDot from '@/components/TaskStatusDot.vue';

const route = useRoute();
const router = useRouter();
const taskId = Number(route.params.id);

const progress = ref<ImportTaskProgressVO | null>(null);
const items = ref<Array<{ seq: number; itemStatus: ImportItemStatus; leadId: number | null; customerName: string | null; reason: string | null }>>([]);
const errorText = ref('');
const loading = ref(true);

const isFinal = computed(() =>
  progress.value !== null && ['SUCCESS', 'PARTIAL', 'FAILED'].includes(progress.value.status),
);
const percent = computed(() => progress.value?.percent ?? 0);
const processingSeq = computed(() => items.value.find((i) => i.itemStatus === 'RUNNING')?.seq ?? null);

let pollTimer: number | null = null;
function stopPolling(): void {
  if (pollTimer !== null) {
    window.clearTimeout(pollTimer);
    pollTimer = null;
  }
}

async function refresh(): Promise<void> {
  try {
    progress.value = await getImportTaskProgress(taskId);
    items.value = await getImportTaskItems(taskId);
    errorText.value = '';
  } catch {
    errorText.value = '任务进度加载失败，请稍后重试';
  } finally {
    loading.value = false;
  }
}

function tick(): void {
  pollTimer = null;
  if (isFinal.value) return;
  void refresh().then(() => {
    if (!isFinal.value && !errorText.value) pollTimer = window.setTimeout(tick, 2000);
  });
}

void refresh().then(() => {
  if (!isFinal.value) pollTimer = window.setTimeout(tick, 2000);
});
onUnmounted(stopPolling);

/** 逐条结果 pill 语义：SUCCESS 绿 / DUPLICATE 中性（不报警）/ FAILED 红 /
 *  DEGRADED warning（线索已创建，仅 AI 识别降级，不能标失败红）/ 中间态灰蓝 */
function pillTone(status: ImportItemStatus): string {
  if (status === 'SUCCESS') return 'is-success';
  if (status === 'FAILED') return 'is-failed';
  if (status === 'DEGRADED') return 'is-degraded';
  if (status === 'DUPLICATE') return 'is-duplicate';
  return 'is-waiting';
}
</script>

<template>
  <div class="task-page">
    <header class="page-head">
      <div class="page-head__row">
        <h1 class="page-head__title">批量导入任务</h1>
        <span class="page-head__id">#T-{{ String(taskId).padStart(4, '0') }}</span>
        <TaskStatusDot v-if="progress" :status="progress.status" />
      </div>
    </header>

    <el-skeleton v-if="loading" :rows="6" animated />

    <template v-else-if="progress">
      <section class="panel">
        <p class="progress-title">处理进度</p>
        <el-progress
          :percentage="percent"
          :stroke-width="10"
          :status="progress.status === 'FAILED' ? 'exception' : undefined"
        />
        <p class="progress-meta">
          已处理 <span class="is-mono">{{ progress.processed }} / {{ progress.total }}</span> 条
          <span class="dot-sep">·</span> 成功 <span class="is-mono">{{ progress.success }}</span>
          <span class="dot-sep">·</span> 重复跳过 <span class="is-mono">{{ progress.duplicate }}</span>
          <span class="dot-sep">·</span>
          <!-- DEF-04：转人工单列（manual）。DEGRADED 线索已入库仅抽取降级，语义 warning 非失败红 -->
          <span :class="{ 'is-manual': progress.manual > 0 }">转人工 <span class="is-mono">{{ progress.manual }}</span></span>
          <span class="dot-sep">·</span>
          <span :class="{ 'is-fail': progress.fail > 0 }">失败 <span class="is-mono">{{ progress.fail }}</span></span>
        </p>
        <p v-if="progress.status === 'PENDING'" class="progress-hint">任务已提交，等待处理</p>
        <p v-else-if="progress.status === 'RUNNING' && processingSeq !== null" class="progress-hint">
          正在识别第 {{ processingSeq }} 条 …（模型解析中）
        </p>
      </section>

      <section class="panel">
        <h2 class="section-title">逐条结果</h2>
        <div class="items" role="table" aria-label="任务逐条结果">
          <div class="items__row items__row--head" role="row">
            <span>序号</span><span>客户</span><span>结果</span><span>说明</span>
          </div>
          <div
            v-for="item in items"
            :key="item.seq"
            class="items__row"
            :class="{ 'is-fail-row': item.itemStatus === 'FAILED' }"
            role="row"
          >
            <span class="is-mono">{{ String(item.seq).padStart(3, '0') }}</span>
            <span>{{ item.customerName ?? '（无法识别）' }}</span>
            <span>
              <span class="pill" :class="pillTone(item.itemStatus)">{{ IMPORT_ITEM_STATUS_LABEL[item.itemStatus] }}</span>
            </span>
            <span class="items__reason">
              <template v-if="item.leadId !== null">
                <!-- SUCCESS 正常建档；DEGRADED 降级建档（线索已创建，leadId 有值） -->
                创建线索 <router-link class="items__link" :to="`/leads/${item.leadId}`">#{{ item.leadId }}</router-link>
                <template v-if="item.itemStatus === 'DEGRADED'">（{{ item.reason ?? 'AI 识别降级，请人工补全' }}）</template>
              </template>
              <template v-else>{{ item.reason ?? '—' }}</template>
            </span>
          </div>
        </div>

        <div class="page-actions">
          <el-button type="primary" @click="router.push('/leads')">查看已创建线索</el-button>
          <el-button @click="router.push('/leads/import/batch')">返回列表</el-button>
        </div>
      </section>
    </template>

    <section v-else class="panel">
      <p class="progress-hint">{{ errorText }}</p>
      <el-button @click="() => refresh()">重试</el-button>
    </section>
  </div>
</template>

<style scoped>
.page-head__row { display: flex; align-items: center; gap: var(--space-3); }
.page-head__title { margin: 0; font-size: var(--text-xl); font-weight: var(--fw-semibold); letter-spacing: -0.01em; color: var(--fg); }
.page-head__id { font-family: var(--font-mono); font-size: var(--text-base); color: var(--fg-3); }

.panel { background: var(--surface); border: 1px solid var(--border); border-radius: var(--radius-md); padding: var(--space-4); }
.panel + .panel { margin-top: var(--space-4); }
.progress-title { margin: 0 0 var(--space-2); font-size: var(--text-base); font-weight: var(--fw-medium); color: var(--fg); }
.progress-meta { margin-top: var(--space-2); font-size: var(--text-sm); color: var(--fg-2); }
/* DEF-04：转人工 = warning 语义（--warning-text），失败 = danger，两者不可混色 */
.progress-meta .is-manual { color: var(--warning-text); }
.progress-meta .is-fail { color: var(--danger); }
.progress-hint { margin: var(--space-2) 0 0; font-size: var(--text-sm); color: var(--fg-3); }
.dot-sep { margin: 0 var(--space-1); color: var(--fg-3); }
.is-mono { font-family: var(--font-mono); }

.section-title { margin: 0 0 var(--space-3); font-size: var(--text-lg); font-weight: var(--fw-semibold); color: var(--fg); }
.items { display: flex; flex-direction: column; }
.items__row {
  display: grid;
  grid-template-columns: 64px 1.2fr 90px 1.6fr;
  gap: var(--space-3);
  align-items: center;
  padding: var(--space-2);
  font-size: var(--text-sm);
  color: var(--fg-2);
}
.items__row--head { color: var(--fg-3); font-weight: var(--fw-medium); border-bottom: 1px solid var(--border-soft); }
.is-fail-row { background: var(--danger-subtle, var(--neutral-subtle)); border-radius: var(--radius-sm); }

.pill {
  display: inline-flex; align-items: center; height: 20px; padding: 0 8px;
  border-radius: var(--radius-full); font-size: var(--text-xs); font-weight: var(--fw-medium);
}
.pill.is-success { color: var(--success-text); background: var(--success-subtle); }
.pill.is-failed { color: var(--danger); background: var(--danger-subtle, var(--neutral-subtle)); }
.pill.is-degraded { color: var(--warning-text); background: var(--warning-subtle); border: 1px solid var(--warning-border); }
.pill.is-duplicate { color: var(--neutral-text); background: var(--neutral-subtle); }
.pill.is-waiting { color: var(--meta); background: var(--surface-3); }

.items__reason { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.items__link { color: var(--primary); }
.page-actions { display: flex; gap: var(--space-3); margin-top: var(--space-4); }
</style>
