<script setup lang="ts">
/**
 * /leads 全量线索管理（T1-12）
 * - 筛选/搜索/分页与 URL query 同步（刷新、前进后退可复现，AC-12）
 * - 新建走 openapi 1.2.1 的「手工录入模式」；编辑走 PATCH LeadUpdateRequest
 * - 删除二次确认（AC-10）；空状态给出可点入口（AC-13）
 * - 主管在 R1 只读：隐藏新建/编辑/删除
 */
import { computed, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import type { FormInstance, FormRules } from 'element-plus';
import { Plus, Refresh, Search } from '@element-plus/icons-vue';
import { createLead, deleteLead, getLead, updateLead } from '@/api/lead';
import type { CompanyScale, IntentCategory, LeadCreateRequest, LeadSortField, LeadUpdateRequest, LeadVO, SortOrder } from '@/types/lead';
import { useLeadQuery } from '@/composables/useLeadQuery';
import { useUserStore } from '@/stores/user';
import { COMPANY_SCALE_LABEL, INTENT_LABEL, leadDisplayName } from '@/utils/format';
import LeadTable from '@/components/LeadTable.vue';
import EmptyState from '@/components/EmptyState.vue';

const router = useRouter();
const userStore = useUserStore();
const canManage = computed(() => userStore.isSpecialist);

const {
  rows,
  total,
  loading,
  error,
  isEmpty,
  query,
  statusOptions,
  categoryOptions,
  fetchList,
  search,
  reset,
  changePage,
  changeSize,
  changeSort,
} = useLeadQuery({ scope: 'all', defaultSort: 'created_at' });

const categorySelectOptions = categoryOptions.filter((item) => item.value !== '');

/** 是否处于「有筛选/搜索」状态（E-2：空态拆两态的判定依据） */
const hasFilter = computed(() => Boolean(query.q.trim() || query.status || query.intentCategory));

/** 清空筛选并回到第 1 页（E-2「筛选无结果」态的出口动作） */
function clearFilters(): void {
  query.q = '';
  query.status = '';
  query.intentCategory = '';
  void search();
}

/* ------------------------------ 表格交互 ------------------------------ */
function goDetail(row: LeadVO): void {
  void router.push(`/leads/${row.id}`);
}

function handleSort(payload: { sort: LeadSortField; order: SortOrder }): void {
  void changeSort(payload.sort, payload.order);
}

/**
 * 删除确认标题：把用户真正用来识别这条线索的东西（客户名/公司名）放进标题，
 * 而不是他只在此处才看得到的编号（D-5 已撤销「编号前缀」展示格式）。
 */
function deleteConfirmTitle(row: LeadVO): string {
  const identity = leadDisplayName(row);
  return identity ? `确认删除线索 #${row.id}（${identity}）？` : `确认删除线索 #${row.id}？`;
}

async function confirmDelete(row: LeadVO): Promise<void> {
  try {
    await ElMessageBox.confirm('删除后不可恢复，跟进记录将一并移除。', deleteConfirmTitle(row), {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      confirmButtonClass: 'el-button--danger',
    });
  } catch {
    return;
  }
  try {
    await deleteLead(row.id);
    ElMessage.success('线索已删除');
    await fetchList();
  } catch {
    // 错误提示由 http 拦截器统一给出
  }
}

/* ------------------------------ 新建 / 编辑 ------------------------------ */
type DialogMode = 'create' | 'edit';

const dialogVisible = ref(false);
const dialogMode = ref<DialogMode>('create');
const editingId = ref<number | null>(null);
const submitting = ref(false);
const formRef = ref<FormInstance>();

const form = reactive({
  subjectTitle: '',
  originalText: '',
  customerName: '',
  companyName: '',
  contactInfo: '',
  intentCategory: '' as IntentCategory | '',
  demandSummary: '',
  budgetAmount: undefined as number | undefined,
  expectedTime: '',
  companyScale: '' as CompanyScale | '',
  remark: '',
});

const dialogTitle = computed(() =>
  dialogMode.value === 'create' ? '新建线索' : `编辑线索 #${editingId.value ?? ''}`,
);

const formRules = computed<FormRules<typeof form>>(() => ({
  // AC-08：客户名称 / 需求要点 / 邮件正文必填，逐字段提示
  customerName: [{ required: true, message: '请输入客户名称', trigger: 'blur' }],
  demandSummary: [{ required: true, message: '请输入需求要点', trigger: 'blur' }],
  originalText:
    dialogMode.value === 'create'
      ? [{ required: true, message: '请粘贴客户邮件正文（指纹去重依赖该字段）', trigger: 'blur' }]
      : [],
}));

const intentOptions = (Object.keys(INTENT_LABEL) as IntentCategory[]).map((value) => ({
  value,
  label: INTENT_LABEL[value],
}));

const scaleOptions = (Object.keys(COMPANY_SCALE_LABEL) as CompanyScale[]).map((value) => ({
  value,
  label: COMPANY_SCALE_LABEL[value],
}));

function resetForm(): void {
  form.subjectTitle = '';
  form.originalText = '';
  form.customerName = '';
  form.companyName = '';
  form.contactInfo = '';
  form.intentCategory = '';
  form.demandSummary = '';
  form.budgetAmount = undefined;
  form.expectedTime = '';
  form.companyScale = '';
  form.remark = '';
  formRef.value?.clearValidate();
}

function openCreate(): void {
  dialogMode.value = 'create';
  editingId.value = null;
  resetForm();
  dialogVisible.value = true;
}

async function openEdit(row: LeadVO): Promise<void> {
  dialogMode.value = 'edit';
  editingId.value = row.id;
  resetForm();
  dialogVisible.value = true;
  try {
    // LeadVO 不含 contactInfo/budgetAmount/expectedTime/companyScale，编辑前取详情回填
    const detail = await getLead(row.id);
    form.subjectTitle = detail.subjectTitle ?? '';
    form.customerName = detail.customerName ?? '';
    form.companyName = detail.companyName ?? '';
    form.contactInfo = detail.contactInfo ?? '';
    form.intentCategory = detail.intentCategory ?? '';
    form.demandSummary = detail.demandSummary ?? '';
    form.budgetAmount = detail.budgetAmount ?? undefined;
    form.expectedTime = detail.expectedTime ?? '';
    form.companyScale = (detail.companyScale as CompanyScale | null) ?? '';
    form.remark = '';
  } catch {
    // 详情拉取失败时仍保留空表单，用户可重试
  }
}

/** 提交对象（仅保留有值的字段，避免提交无意义的空字符串） */
async function submitForm(): Promise<void> {
  if (!formRef.value) return;
  const valid = await formRef.value.validate().catch(() => false);
  if (!valid) return;

  submitting.value = true;
  try {
    if (dialogMode.value === 'create') {
      const payload: LeadCreateRequest = {
        originalText: form.originalText,
        customerName: form.customerName,
        demandSummary: form.demandSummary,
      };
      if (form.subjectTitle) payload.subjectTitle = form.subjectTitle;
      if (form.companyName) payload.companyName = form.companyName;
      if (form.contactInfo) payload.contactInfo = form.contactInfo;
      if (form.intentCategory) payload.intentCategory = form.intentCategory;
      if (form.budgetAmount !== undefined) payload.budgetAmount = form.budgetAmount;
      if (form.expectedTime) payload.expectedTime = form.expectedTime;
      if (form.companyScale) payload.companyScale = form.companyScale;
      if (form.remark) payload.remark = form.remark;
      await createLead(payload);
      ElMessage.success('线索已创建，系统已完成优先级评分');
    } else if (editingId.value !== null) {
      const payload: LeadUpdateRequest = {
        customerName: form.customerName,
        companyName: form.companyName,
        contactInfo: form.contactInfo,
        demandSummary: form.demandSummary,
        remark: form.remark,
        budgetAmount: form.budgetAmount,
        expectedTime: form.expectedTime || undefined,
      };
      if (form.intentCategory) payload.intentCategory = form.intentCategory;
      if (form.companyScale) payload.companyScale = form.companyScale;
      await updateLead(editingId.value, payload);
      ElMessage.success('线索已更新');
    }
    dialogVisible.value = false;
    await fetchList();
  } catch {
    // 40001 字段级错误与其余错误由拦截器弹出后端 message
  } finally {
    submitting.value = false;
  }
}
</script>

<template>
  <div class="lead-list">
    <header class="page-head">
      <h1 class="page-head__title">线索管理</h1>
      <p class="page-head__desc">全量线索的检索、录入与维护；系统按评分规则自动排好优先级。</p>
    </header>

    <div class="toolbar">
      <el-input
        v-model="query.q"
        class="toolbar__search"
        placeholder="搜索客户名称 / 公司名称 / 需求要点"
        clearable
        @keyup.enter="search"
        @clear="search"
      >
        <template #prefix>
          <el-icon :size="16"><Search /></el-icon>
        </template>
      </el-input>

      <el-select v-model="query.intentCategory" class="toolbar__select" placeholder="意图类别" @change="search">
        <el-option v-for="item in categorySelectOptions" :key="item.value" :label="item.label" :value="item.value" />
      </el-select>

      <el-select v-model="query.status" class="toolbar__select" placeholder="状态" @change="search">
        <el-option v-for="item in statusOptions" :key="item.value" :label="item.label" :value="item.value" />
      </el-select>

      <el-button :icon="Search" type="primary" @click="search">查询</el-button>
      <el-button @click="reset">重置</el-button>

      <span class="toolbar__spacer" />

      <el-button :icon="Refresh" @click="fetchList">刷新</el-button>
      <el-button v-if="canManage" type="primary" :icon="Plus" @click="openCreate">新建线索</el-button>
    </div>

    <div class="table-wrap">
      <div v-if="error" class="table-state">
        <p class="table-state__text">列表加载失败，请检查后端服务后重试。</p>
        <el-button type="primary" @click="fetchList">重试</el-button>
      </div>

      <!-- E-2 空态拆两态：全库无数据 vs 筛选无结果，文案与出口动作不同 -->
      <EmptyState
        v-else-if="isEmpty && !hasFilter"
        title="还没有线索"
        description="手工录入一条线索，录入后系统会立即按规则打分并排好优先级。"
      >
        <template #actions>
          <el-button v-if="canManage" type="primary" @click="openCreate">新建线索</el-button>
          <!-- R2 / T2-05：单条粘贴导入页已上线，空态给出真入口（仅专员，导入是销售动作） -->
          <el-button v-if="canManage" @click="router.push('/leads/import')">粘贴导入</el-button>
        </template>
      </EmptyState>

      <EmptyState
        v-else-if="isEmpty && hasFilter"
        title="没有匹配的线索"
        description="试试调整筛选条件或清空搜索关键词。"
      >
        <template #actions>
          <el-button type="primary" @click="clearFilters">清空筛选</el-button>
        </template>
      </EmptyState>

      <template v-else>
        <LeadTable
          :rows="rows"
          :loading="loading"
          mode="leads"
          :can-manage="canManage"
          :sort="query.sort"
          :order="query.order"
          @view="goDetail"
          @edit="openEdit"
          @remove="confirmDelete"
          @sort="handleSort"
        />

        <div class="pager">
          <span class="pager__total">共 {{ total }} 条</span>
          <el-pagination
            background
            :current-page="query.page"
            :page-size="query.limit"
            :page-sizes="[10, 20, 50, 100]"
            :total="total"
            layout="prev, pager, next, sizes, jumper"
            @current-change="changePage"
            @size-change="changeSize"
          />
        </div>
      </template>
    </div>

    <el-dialog
      v-model="dialogVisible"
      :title="dialogTitle"
      width="760px"
      :close-on-click-modal="false"
      destroy-on-close
    >
      <el-form ref="formRef" :model="form" :rules="formRules" label-width="96px">
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="客户名称" prop="customerName">
              <el-input v-model="form.customerName" placeholder="如：陈先生" maxlength="100" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="所属公司">
              <el-input v-model="form.companyName" placeholder="如：广州鑫茂五金制品有限公司" maxlength="150" />
            </el-form-item>
          </el-col>
        </el-row>

        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="联系方式">
              <el-input v-model="form.contactInfo" placeholder="电话 / 邮箱" maxlength="150" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="意图类别">
              <el-select v-model="form.intentCategory" placeholder="请选择" clearable style="width: 100%">
                <el-option v-for="item in intentOptions" :key="item.value" :label="item.label" :value="item.value" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>

        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="预算金额">
              <el-input-number v-model="form.budgetAmount" :min="0" :step="1000" controls-position="right" style="width: 100%" placeholder="单位：元" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="期望时间">
              <el-date-picker v-model="form.expectedTime" type="date" value-format="YYYY-MM-DD" placeholder="选择日期" style="width: 100%" />
            </el-form-item>
          </el-col>
        </el-row>

        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="公司规模">
              <el-select v-model="form.companyScale" placeholder="请选择" clearable style="width: 100%">
                <el-option v-for="item in scaleOptions" :key="item.value" :label="item.label" :value="item.value" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="邮件主题">
              <el-input v-model="form.subjectTitle" placeholder="可留空" maxlength="255" />
            </el-form-item>
          </el-col>
        </el-row>

        <el-form-item label="需求要点" prop="demandSummary">
          <el-input
            v-model="form.demandSummary"
            type="textarea"
            :rows="2"
            maxlength="500"
            show-word-limit
            placeholder="如：询 304 不锈钢管件报价，需 500 米，月底前要货"
          />
        </el-form-item>

        <el-form-item v-if="dialogMode === 'create'" label="邮件正文" prop="originalText">
          <el-input
            v-model="form.originalText"
            type="textarea"
            :rows="5"
            placeholder="粘贴客户邮件原文。系统据此做内容指纹去重，同一封邮件重复提交会被识别。"
          />
        </el-form-item>

        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="2" maxlength="500" placeholder="可留空" />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitForm">
          {{ dialogMode === 'create' ? '创建' : '保存' }}
        </el-button>
      </template>
    </el-dialog>
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

.toolbar {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  margin-bottom: var(--space-4);
}

.toolbar__search {
  width: 280px;
}

.toolbar__select {
  width: 150px;
}

.toolbar__spacer {
  flex: 1;
}

.table-wrap {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  padding: var(--space-3);
}

.table-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-3);
  padding: var(--space-10) 0;
}

.table-state__text {
  font-size: var(--text-base);
  color: var(--fg-2);
}

.pager {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: var(--space-3);
}

.pager__total {
  font-size: var(--text-sm);
  color: var(--fg-3);
}
</style>
