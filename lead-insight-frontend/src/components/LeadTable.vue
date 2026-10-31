<script setup lang="ts">
/**
 * LeadTable —— 线索表格（WorkbenchView 与 LeadListView 共用，禁止复制代码）。
 * 两视图差异仅通过 props（mode / canManage / canClaim）体现。
 *
 * 列序（team-lead 冻结 10 列，顺序即列序）：
 * 优先级 → 状态 → 类别 → 客户名称 → 所属公司 → 需求要点 → 期望时间 → 负责人 → 创建时间 → 操作
 * 前 7 列表达「这条线索是什么」，负责人/创建时间表达「归谁、什么时候来的」，操作列单独收尾。
 * 契约：openapi v1.2.1 的 LeadVO 含 expectedTime 与 ownerName，故直接使用 LeadVO。
 */
import { computed } from 'vue';
import type { LeadSortField, LeadVO, SortOrder } from '@/types/lead';
import { formatDate, formatDateTime, orDash } from '@/utils/format';
import CategoryTag from '@/components/CategoryTag.vue';
import PriorityTag from '@/components/PriorityTag.vue';
import StatusDot from '@/components/StatusDot.vue';

const props = withDefaults(
  defineProps<{
    rows: LeadVO[];
    loading?: boolean;
    mode?: 'leads' | 'workbench';
    /** 是否可管理（新建/编辑/删除）。主管在 R1 只读，传 false */
    canManage?: boolean;
    /** 是否可认领（仅销售专员） */
    canClaim?: boolean;
    sort?: LeadSortField;
    order?: SortOrder;
  }>(),
  {
    loading: false,
    mode: 'leads',
    canManage: false,
    canClaim: false,
    sort: 'priority_score',
    order: 'desc',
  },
);

const emit = defineEmits<{
  view: [row: LeadVO];
  edit: [row: LeadVO];
  remove: [row: LeadVO];
  claim: [row: LeadVO];
  sort: [payload: { sort: LeadSortField; order: SortOrder }];
}>();

/** el-table 的排序方向字面量（与 Element Plus TableSortOrder 一致） */
type SortDirection = 'ascending' | 'descending';

const defaultSort = computed<{ prop: string; order: SortDirection }>(() => ({
  prop: props.sort === 'created_at' ? 'createdAt' : 'priorityScore',
  order: props.order === 'asc' ? 'ascending' : 'descending',
}));

function handleSortChange(payload: { prop: string | null; order: SortDirection | null }): void {
  const sort: LeadSortField = payload.prop === 'createdAt' ? 'created_at' : 'priority_score';
  const order: SortOrder = payload.order === 'ascending' ? 'asc' : 'desc';
  emit('sort', { sort, order });
}

/** 认领按钮仅在「新线索且无人认领」时出现 */
function canClaimRow(row: LeadVO): boolean {
  return props.mode === 'workbench' && props.canClaim && row.status === 'NEW' && row.ownerId === null;
}

/** 负责人：未认领是有业务含义的状态，不显示为缺数据 */
function ownerText(row: LeadVO): string {
  return row.ownerName?.trim() || '未认领';
}

function isUnassigned(row: LeadVO): boolean {
  return !row.ownerName?.trim();
}
</script>

<template>
  <el-table
    class="lead-table"
    :data="rows"
    :default-sort="defaultSort"
    row-key="id"
    stripe
    border
    size="default"
    :header-cell-style="{ height: '44px' }"
    @sort-change="handleSortChange"
  >
    <el-table-column label="优先级" prop="priorityScore" width="150" sortable="custom">
      <template #default="{ row }">
        <PriorityTag :level="row.priorityLevel" :score="row.priorityScore" />
      </template>
    </el-table-column>

    <el-table-column label="状态" width="120">
      <template #default="{ row }">
        <StatusDot :status="row.status" />
      </template>
    </el-table-column>

    <el-table-column label="类别" width="110">
      <template #default="{ row }">
        <CategoryTag :category="row.intentCategory" />
      </template>
    </el-table-column>

    <el-table-column label="客户名称" min-width="130" show-overflow-tooltip>
      <template #default="{ row }">{{ orDash(row.customerName) }}</template>
    </el-table-column>

    <el-table-column label="所属公司" min-width="190" show-overflow-tooltip>
      <template #default="{ row }">{{ orDash(row.companyName) }}</template>
    </el-table-column>

    <!-- 需求要点：占剩余宽度（min-width）+ 单行省略，是缓解 1280px 宽度的正确手段 -->
    <el-table-column label="需求要点" min-width="220" show-overflow-tooltip>
      <template #default="{ row }">{{ orDash(row.demandSummary) }}</template>
    </el-table-column>

    <el-table-column label="期望时间" width="130">
      <template #default="{ row }">
        <span class="num">{{ formatDate(row.expectedTime) }}</span>
      </template>
    </el-table-column>

    <el-table-column label="负责人" width="110">
      <template #default="{ row }">
        <span :class="{ 'lead-table__owner--none': isUnassigned(row) }">{{ ownerText(row) }}</span>
      </template>
    </el-table-column>

    <el-table-column label="创建时间" prop="createdAt" width="170" sortable="custom">
      <template #default="{ row }">
        <span class="num">{{ formatDateTime(row.createdAt) }}</span>
      </template>
    </el-table-column>

    <el-table-column label="操作" width="170" fixed="right">
      <template #default="{ row }">
        <div class="lead-table__actions">
          <el-button link type="primary" @click="emit('view', row)">查看</el-button>

          <template v-if="mode === 'leads' && canManage">
            <span class="row-extra">
              <el-button link @click="emit('edit', row)">编辑</el-button>
              <el-button link type="danger" @click="emit('remove', row)">删除</el-button>
            </span>
          </template>

          <el-button v-if="canClaimRow(row)" link type="primary" @click="emit('claim', row)">
            认领
          </el-button>
        </div>
      </template>
    </el-table-column>
  </el-table>
</template>

<style scoped>
.lead-table {
  width: 100%;
}

.lead-table__owner--none {
  color: var(--meta);
}

.lead-table__actions {
  display: flex;
  align-items: center;
  gap: var(--space-1);
}

/* 行 hover 才露出次要操作（编辑/删除），键盘聚焦时同样可见 */
.row-extra {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
  opacity: 0;
  transition: opacity var(--duration-fast) var(--ease-standard);
}

.lead-table :deep(.el-table__row:hover) .row-extra,
.lead-table :deep(.el-table__row:focus-within) .row-extra,
.row-extra:focus-within {
  opacity: 1;
}
</style>
