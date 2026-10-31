<script setup lang="ts">
/**
 * /workbench 我的待跟进（T1-14）
 * 与 /leads 共用 LeadTable + useLeadQuery，差异只在：ownerId 收敛为当前用户、状态枚举限 NEW/FOLLOWING、
 * 主操作为「认领 + 查看详情」。
 *
 * 说明：openapi 的 status 为单值参数，无法一次传 NEW+FOLLOWING。故状态做成枚举筛选
 * （全部 / 新线索 / 跟进中），默认「全部」且不传 status，由 ownerId 收敛出「我的」范围。
 */
import { computed } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { Refresh, Search } from '@element-plus/icons-vue';
import { claimLead } from '@/api/lead';
import type { LeadSortField, LeadVO, SortOrder } from '@/types/lead';
import { useLeadQuery } from '@/composables/useLeadQuery';
import { useUserStore } from '@/stores/user';
import { leadDisplayName } from '@/utils/format';
import LeadTable from '@/components/LeadTable.vue';
import EmptyState from '@/components/EmptyState.vue';

const router = useRouter();
const userStore = useUserStore();
const canClaim = computed(() => userStore.isSpecialist);

const {
  rows,
  total,
  loading,
  error,
  isEmpty,
  query,
  statusOptions,
  fetchList,
  search,
  reset,
  changePage,
  changeSize,
  changeSort,
} = useLeadQuery({ scope: 'mine' });

function goDetail(row: LeadVO): void {
  void router.push(`/leads/${row.id}`);
}

function handleSort(payload: { sort: LeadSortField; order: SortOrder }): void {
  void changeSort(payload.sort, payload.order);
}

async function handleClaim(row: LeadVO): Promise<void> {
  try {
    await claimLead(row.id);
    const identity = leadDisplayName(row);
    ElMessage.success(
      `已认领线索 #${row.id}${identity ? `（${identity}）` : ''}，状态更新为「跟进中」`,
    );
    await fetchList();
  } catch {
    // 42201 等错误由拦截器提示后端 message
    await fetchList();
  }
}
</script>

<template>
  <div class="workbench">
    <header class="page-head">
      <h1 class="page-head__title">我的待跟进</h1>
      <p class="page-head__desc">按优先级分数降序排列，同分按创建时间升序——先跟最该跟的那一条。</p>
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

      <el-select v-model="query.status" class="toolbar__select" placeholder="状态" @change="search">
        <el-option v-for="item in statusOptions" :key="item.value" :label="item.label" :value="item.value" />
      </el-select>

      <el-button :icon="Search" type="primary" @click="search">查询</el-button>
      <el-button @click="reset">重置</el-button>

      <span class="toolbar__spacer" />

      <el-button :icon="Refresh" @click="fetchList">刷新</el-button>
    </div>

    <div class="table-wrap">
      <div v-if="error" class="table-state">
        <p class="table-state__text">待跟进列表加载失败，请检查后端服务后重试。</p>
        <el-button type="primary" @click="fetchList">重试</el-button>
      </div>

      <EmptyState
        v-else-if="isEmpty"
        title="当前没有待跟进的线索"
        description="你名下暂无新线索或跟进中的线索。可以到「全部线索」认领一条，或先手工录入。"
      >
        <template #actions>
          <el-button type="primary" @click="router.push('/leads')">去全部线索</el-button>
        </template>
      </EmptyState>

      <template v-else>
        <LeadTable
          :rows="rows"
          :loading="loading"
          mode="workbench"
          :can-claim="canClaim"
          :sort="query.sort"
          :order="query.order"
          @view="goDetail"
          @claim="handleClaim"
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
