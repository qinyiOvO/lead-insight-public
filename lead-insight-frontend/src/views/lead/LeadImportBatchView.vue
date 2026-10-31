<script setup lang="ts">
/**
 * /leads/import/batch 批量导入（T3-03，UIUX §7.5.6 + R-12）。
 * 双入口（粘贴 / 选文件）共用一个 textarea、一个提交按钮、一个接口：
 * 文件由前端 FileReader + TextDecoder 读成文本回显，不走 el-upload、不传文件本体。
 * 提交 -> POST /import-tasks (202) -> 跳 /tasks/:id；下方「近期导入任务」为找回入口（非新路由）。
 */
import { computed, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { Upload } from '@element-plus/icons-vue';
import { createBatchImport, listImportTasks } from '@/api/importTask';
import type { ImportTaskVO } from '@/types/import-task';
import { formatDateTime } from '@/utils/format';
import EmptyState from '@/components/EmptyState.vue';
import TaskStatusDot from '@/components/TaskStatusDot.vue';

const router = useRouter();

const EXAMPLE_PLACEHOLDER =
  '客户：广州振华五金制品有限公司 · 我方计划采购 304 不锈钢管 500 米，采购预算 60 万元，希望 7 天内拿到正式报价并签订合同。\n----------\n客户：深圳恒宇电子科技有限公司 · 想了解产品目录与库存情况。';

const text = ref('');
const submitting = ref(false);
// 入口 B 状态
const fileInput = ref<HTMLInputElement | null>(null);
const fileInfo = ref<string | null>(null);
const readError = ref(false);

/** 按分隔线切分（独立一行 3 个以上连字符），过滤空段 */
const segments = computed(() =>
  text.value
    .split(/^\s*-{3,}\s*$/m)
    .map((seg) => seg.trim())
    .filter((seg) => seg.length > 0),
);
const segmentCount = computed(() => segments.value.length);
const overLimit = computed(() => segmentCount.value > 200);

function pickFile(): void {
  fileInput.value?.click();
}

async function onFileChange(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  input.value = ''; // 允许重复选同一个文件
  if (!file) return;
  readError.value = false;
  const lower = file.name.toLowerCase();
  if (!lower.endsWith('.txt') && !lower.endsWith('.eml')) {
    ElMessage.error('仅支持 .txt / .eml 文本文件');
    return;
  }
  if (file.size > 2 * 1024 * 1024) {
    ElMessage.error('文件过大，请拆分后重试');
    return;
  }
  try {
    const buffer = await file.arrayBuffer();
    let content: string;
    try {
      content = new TextDecoder('utf-8', { fatal: true }).decode(buffer);
    } catch {
      content = new TextDecoder('gbk').decode(buffer);
      ElMessage.warning('已按 GBK 编码读取');
    }
    text.value = content;
    fileInfo.value = `${file.name} · ${(file.size / 1024 / 1024).toFixed(1)} MB`;
  } catch {
    readError.value = true;
  }
}

async function submit(): Promise<void> {
  if (segmentCount.value === 0) {
    ElMessage.warning('请先粘贴邮件文本或选择文件');
    return;
  }
  if (overLimit.value) {
    ElMessage.error('单次最多 200 条，请分批导入');
    return;
  }
  submitting.value = true;
  try {
    const task = await createBatchImport({ textList: segments.value });
    ElMessage.success(`任务 ${task.taskNo} 已提交`);
    await router.push(`/tasks/${task.id}`);
  } catch {
    submitting.value = false;
  }
}

// ---- 近期导入任务（可达性补丁：最近 5 条，无分页/筛选/删除） ----
const recent = ref<ImportTaskVO[]>([]);
const recentLoading = ref(false);
const recentError = ref(false);

async function loadRecent(): Promise<void> {
  recentLoading.value = true;
  recentError.value = false;
  try {
    const page = await listImportTasks({ page: 1, limit: 5 });
    recent.value = page.items;
  } catch {
    recentError.value = true;
  } finally {
    recentLoading.value = false;
  }
}

onMounted(loadRecent);
</script>

<template>
  <div class="batch-page">
    <header class="page-head">
      <h1 class="page-head__title">批量导入线索</h1>
      <p class="page-head__desc">多条邮件文本按分隔线切分，一次性创建导入任务；系统异步逐条识别并打分。</p>
    </header>

    <section class="panel">
      <div class="entry-row">
        <span class="entry-row__label">输入方式</span>
        <span class="entry-row__hint">粘贴多段文本，或选择 .txt / .eml 文件（内容会回显到下方输入框，可编辑后再提交）</span>
        <el-button :icon="Upload" @click="pickFile">选择文件</el-button>
        <input ref="fileInput" type="file" accept=".txt,.eml" class="file-input" @change="onFileChange" />
      </div>
      <p v-if="fileInfo" class="file-meta">{{ fileInfo }}</p>
      <p v-if="readError" class="read-error">
        文件读取失败
        <el-button link type="primary" @click="pickFile">重试</el-button>
      </p>

      <el-input
        v-model="text"
        type="textarea"
        :rows="10"
        :placeholder="EXAMPLE_PLACEHOLDER"
        :disabled="submitting"
      />
      <div class="import-actions">
        <span class="segment-count" :class="{ 'is-over': overLimit }">已识别 {{ segmentCount }} 条（按分隔线切分）</span>
        <el-button type="primary" :loading="submitting" @click="submit">开始批量识别</el-button>
      </div>
    </section>

    <section class="panel recent">
      <h2 class="recent__title">近期导入任务</h2>
      <el-skeleton v-if="recentLoading" :rows="3" animated />
      <template v-else-if="recentError">
        <p class="recent__error">任务列表加载失败</p>
        <el-button @click="loadRecent">点击重试</el-button>
      </template>
      <EmptyState
        v-else-if="recent.length === 0"
        title="暂无导入任务"
        description="暂无导入任务，粘贴邮件文本提交后会在这里显示进度。"
      />
      <div v-else class="recent__table" role="table" aria-label="近期导入任务列表">
        <div class="recent__row recent__row--head" role="row">
          <span>任务号</span><span>提交时间</span><span>状态</span><span class="is-num">进度</span>
        </div>
        <button
          v-for="task in recent"
          :key="task.id"
          type="button"
          class="recent__row recent__row--link"
          @click="router.push(`/tasks/${task.id}`)"
        >
          <span class="is-mono recent__taskno" :title="task.taskNo">{{ task.taskNo }}</span>
          <span>{{ formatDateTime(task.createdAt) }}</span>
          <span><TaskStatusDot :status="task.status" /> <span class="is-mono">{{ task.processedCount }}/{{ task.totalCount }}</span></span>
          <span class="is-num is-mono">{{ task.successCount }}/{{ task.totalCount }}</span>
        </button>
      </div>
    </section>
  </div>
</template>

<style scoped>
.page-head { margin-bottom: var(--space-4); }
.page-head__title { font-size: var(--text-xl); font-weight: var(--fw-semibold); letter-spacing: -0.01em; color: var(--fg); }
.page-head__desc { margin-top: var(--space-1); font-size: var(--text-base); color: var(--fg-3); }

.panel { background: var(--surface); border: 1px solid var(--border); border-radius: var(--radius-md); padding: var(--space-4); }
.panel + .panel { margin-top: var(--space-4); }

.entry-row { display: flex; align-items: center; gap: var(--space-3); margin-bottom: var(--space-3); }
.entry-row__label { font-size: var(--text-sm); color: var(--fg-2); font-weight: var(--fw-medium); }
.entry-row__hint { flex: 1; font-size: var(--text-sm); color: var(--fg-3); }
.file-input { display: none; }
.file-meta { margin: 0 0 var(--space-2); font-size: var(--text-sm); color: var(--fg-2); }
.read-error { margin: 0 0 var(--space-2); font-size: var(--text-sm); color: var(--danger-text, var(--danger)); }

.import-actions { display: flex; align-items: center; gap: var(--space-3); margin-top: var(--space-3); }
.segment-count { font-size: var(--text-sm); color: var(--fg-2); }
.segment-count.is-over { color: var(--danger); font-weight: var(--fw-medium); }

.recent__title { margin: 0 0 var(--space-3); font-size: var(--text-lg); font-weight: var(--fw-semibold); color: var(--fg); }
.recent__error { margin: 0 0 var(--space-2); font-size: var(--text-sm); color: var(--danger); }

.recent__table { display: flex; flex-direction: column; }
.recent__row {
  display: grid;
  /* 第 1 列需容纳 22 位等宽任务号（IMPT…），原 110px 会被内容撑破并压到第 2 列 */
  grid-template-columns: 200px 1fr 1.4fr 110px;
  gap: var(--space-3);
  align-items: center;
  padding: var(--space-2) var(--space-2);
  font-size: var(--text-sm);
  color: var(--fg-2);
}
/* grid 项默认 min-width:auto，不置 0 则子项 text-overflow:ellipsis 不生效 */
.recent__row > * { min-width: 0; }
.recent__row--head { color: var(--fg-3); font-weight: var(--fw-medium); border-bottom: 1px solid var(--border-soft); }
.recent__row--link {
  border: 0; background: none; text-align: left; width: 100%;
  cursor: pointer; border-radius: var(--radius-sm);
  transition: background-color 150ms cubic-bezier(0.4, 0, 0.2, 1);
}
.recent__row--link:hover { background: var(--surface-2); }
.is-mono { font-family: var(--font-mono); }
/* 任务号：极端窄屏/超长时可省略展示，完整值由 title 兜住（本项目硬规则） */
.recent__taskno { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.is-num { text-align: right; }
</style>
