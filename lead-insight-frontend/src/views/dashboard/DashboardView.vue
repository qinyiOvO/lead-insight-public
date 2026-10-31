<script setup lang="ts">
/**
 * /dashboard 销售看板（T3-06，UIUX §9）。仅主管（AC-05：专员访问 -> /403）。
 * 7 指标卡 4+3 两行（K-2 + R-0-2 定稿）+ 状态 donut + 类别横向条形 + 双线趋势 + 转化漏斗。
 * R-0-2 裁「平均响应」M1 冻结——不渲染占位卡（虚构功能入口，连骨架都不留）。
 * 转化率卡：主数字 1 位小数 + 底部等宽「分子 ÷ 分母」口径同显；分母 0 显 '—'（判据 v0.6 行 10）。
 */
import { computed, onMounted, ref } from 'vue';
import type { EChartsCoreOption } from 'echarts/core';
import { getDashboardOverview, getDashboardTrend, getStatusDistribution, getCategoryDistribution } from '@/api/dashboard';
import type { DistributionVO, OverviewVO, TrendVO } from '@/types/dashboard';
import { chartColor } from '@/utils/chart';
import BaseChart from '@/components/BaseChart.vue';
import EmptyState from '@/components/EmptyState.vue';

const overview = ref<OverviewVO | null>(null);
const statusDist = ref<DistributionVO[]>([]);
const categoryDist = ref<DistributionVO[]>([]);
const trend = ref<TrendVO[]>([]);
const loading = ref(true);
const errorText = ref('');

const STATUS_COLOR: Record<string, string> = {
  NEW: '--status-new',
  FOLLOWING: '--status-following',
  WON: '--status-won',
  INVALID: '--status-invalid',
};
const CATEGORY_COLOR: Record<string, string> = {
  CONSULT: '--chart-2',
  QUOTE: '--chart-4',
  COOPERATION: '--chart-3',
  COMPLAINT: '--chart-5',
  IRRELEVANT: '--chart-7',
};

/** 转化率口径分母（总量 − 无效），主数字与公式行共用同一判断源（判据 v1.1 行10⑥：分母 0 显 '—' 禁 0%） */
const conversionDenom = computed(() => {
  const o = overview.value;
  return o ? o.totalLeads - o.invalidLeads : 0;
});
/** 转化率主数字（1 位小数）；分母 0 时显 '—'，与公式行同口径（判据 10⑥ 禁 0%/NaN） */
const conversionMain = computed(() => {
  const o = overview.value;
  if (!o || conversionDenom.value <= 0) return '—';
  return `${(o.conversionRate * 100).toFixed(1)}%`;
});
const conversionFormula = computed(() => {
  const o = overview.value;
  if (!o || conversionDenom.value <= 0) return '—';
  return `${o.wonLeads.toLocaleString()} ÷ (${o.totalLeads.toLocaleString()} − ${o.invalidLeads.toLocaleString()})`;
});

const donutOption = computed<EChartsCoreOption>(() => ({
  tooltip: { trigger: 'item' },
  legend: { bottom: 0, icon: 'circle', itemWidth: 8, itemHeight: 8 },
  series: [{
    type: 'pie',
    radius: ['52%', '74%'],
    center: ['50%', '44%'],
    itemStyle: { borderWidth: 2, borderColor: '#fff' },
    label: { show: false },
    data: statusDist.value.map((d) => ({
      name: d.label, value: d.value,
      itemStyle: { color: chartColor(STATUS_COLOR[d.key] ?? '--chart-8') },
    })),
  }],
}));

const barOption = computed<EChartsCoreOption>(() => {
  const sorted = [...categoryDist.value].sort((a, b) => a.value - b.value); // 横向条形：底部最大
  return {
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    grid: { left: 8, right: 32, top: 8, bottom: 8, containLabel: true },
    xAxis: { type: 'value' },
    yAxis: { type: 'category', data: sorted.map((d) => d.label), axisTick: { show: false } },
    series: [{
      type: 'bar', barWidth: 16,
      data: sorted.map((d) => ({ value: d.value, itemStyle: { color: chartColor(CATEGORY_COLOR[d.key] ?? '--chart-8'), borderRadius: [0, 4, 4, 0] } })),
    }],
  };
});

const trendOption = computed<EChartsCoreOption>(() => ({
  tooltip: { trigger: 'axis' },
  legend: { top: 0, icon: 'circle', itemWidth: 8, itemHeight: 8 },
  grid: { left: 8, right: 16, top: 32, bottom: 8, containLabel: true },
  xAxis: { type: 'category', data: trend.value.map((t) => t.date.slice(5)), boundaryGap: false },
  yAxis: { type: 'value' },
  series: [
    { name: '新增线索', type: 'line', smooth: true, showSymbol: false, data: trend.value.map((t) => t.createdCount), lineStyle: { color: chartColor('--chart-1') }, itemStyle: { color: chartColor('--chart-1') } },
    { name: '已成交', type: 'line', smooth: true, showSymbol: false, data: trend.value.map((t) => t.wonCount), lineStyle: { color: chartColor('--success') }, itemStyle: { color: chartColor('--success') } },
  ],
}));

const funnelOption = computed<EChartsCoreOption>(() => {
  const o = overview.value;
  const data = o
    ? [
        { name: '新线索', value: o.newCount },
        { name: '跟进中', value: o.followingCount },
        { name: '已成交', value: o.wonLeads },
      ].map((d, i) => ({ ...d, itemStyle: { color: chartColor(['--chart-1', '--chart-2', '--chart-6'][i] ?? '--chart-8') } }))
    : [];
  return {
    tooltip: { trigger: 'item', formatter: '{b}: {c}' },
    series: [{ type: 'funnel', left: '12%', right: '12%', minSize: '28%', label: { formatter: '{b} {c}' }, data }],
  };
});

async function load(): Promise<void> {
  loading.value = true;
  errorText.value = '';
  try {
    const [o, s, c, t] = await Promise.all([
      getDashboardOverview(), getStatusDistribution(), getCategoryDistribution(), getDashboardTrend(30),
    ]);
    overview.value = o;
    statusDist.value = s;
    categoryDist.value = c;
    trend.value = t;
  } catch {
    errorText.value = '看板数据加载失败';
  } finally {
    loading.value = false;
  }
}

onMounted(load);
</script>

<template>
  <div class="dash">
    <header class="dash-head">
      <h1 class="dash-head__title">销售看板</h1>
      <span class="dash-head__range">时间范围：近 30 天</span>
    </header>

    <el-skeleton v-if="loading" :rows="8" animated />

    <template v-else-if="errorText">
      <div class="panel dash-error">
        <p>{{ errorText }}</p>
        <el-button type="primary" @click="load">重试</el-button>
      </div>
    </template>

    <EmptyState
      v-else-if="overview && overview.totalLeads === 0"
      title="还没有线索数据"
      description="录入或导入线索后，这里会呈现状态、类别与趋势概览。"
    />

    <template v-else-if="overview">
      <section class="dash-metrics" aria-label="核心指标">
        <div class="metric"><span class="metric__label">线索总量</span><span class="metric__value">{{ overview.totalLeads.toLocaleString() }}</span></div>
        <div class="metric"><span class="metric__label">新线索</span><span class="metric__value">{{ overview.newCount.toLocaleString() }}</span></div>
        <div class="metric"><span class="metric__label">跟进中</span><span class="metric__value">{{ overview.followingCount.toLocaleString() }}</span></div>
        <div class="metric"><span class="metric__label">已成交</span><span class="metric__value">{{ overview.wonLeads.toLocaleString() }}</span></div>
        <div class="metric metric--wide"><span class="metric__label">无效线索</span><span class="metric__value">{{ overview.invalidLeads.toLocaleString() }}</span></div>
        <div class="metric metric--wide">
          <span class="metric__label">待人工处理</span>
          <span class="metric__value">{{ overview.pendingManualCount.toLocaleString() }}</span>
          <span class="metric__sub" aria-label="待人工口径">extract_status 为 FAILED/DEGRADED，即已转人工标记来源</span>
        </div>
        <div class="metric metric--wide">
          <span class="metric__label">转化率</span>
          <span class="metric__value">{{ conversionMain }}</span>
          <span class="metric__sub" aria-label="转化率口径">{{ conversionFormula }}</span>
        </div>
      </section>

      <div class="dash-grid-2">
        <section class="panel">
          <h2 class="panel__title">线索状态分布</h2>
          <div class="panel__chart"><BaseChart :option="donutOption" label="线索状态分布环形图" /></div>
        </section>
        <section class="panel">
          <h2 class="panel__title">意图类别分布</h2>
          <div class="panel__chart"><BaseChart :option="barOption" label="意图类别分布条形图" /></div>
        </section>
      </div>

      <section class="panel">
        <h2 class="panel__title">线索趋势（近 30 天）</h2>
        <div class="panel__chart panel__chart--wide"><BaseChart :option="trendOption" label="新增线索与已成交趋势折线图" /></div>
      </section>

      <section class="panel">
        <h2 class="panel__title">转化漏斗</h2>
        <div class="panel__chart panel__chart--funnel"><BaseChart :option="funnelOption" label="新线索到成交的转化漏斗" /></div>
      </section>
    </template>
  </div>
</template>

<style scoped>
.dash-head { display: flex; align-items: baseline; gap: var(--space-3); margin-bottom: var(--space-4); }
.dash-head__title { margin: 0; font-size: var(--text-xl); font-weight: var(--fw-semibold); letter-spacing: -0.01em; color: var(--fg); }
.dash-head__range { font-size: var(--text-sm); color: var(--fg-3); }

.panel { background: var(--surface); border: 1px solid var(--border); border-radius: var(--radius-md); padding: var(--space-4); }
.panel__title { margin: 0 0 var(--space-3); font-size: var(--text-lg); font-weight: var(--fw-semibold); color: var(--fg); }
.panel__chart { height: 280px; }
.panel__chart--wide { height: 300px; }
.panel__chart--funnel { height: 240px; }

/* 7 卡 4+3 两行：12 列网格，行 1 四卡各跨 3 列，行 2 三卡各跨 4 列 */
.dash-metrics { display: grid; grid-template-columns: repeat(12, 1fr); gap: var(--space-4); margin-bottom: var(--space-4); }
.metric { grid-column: span 3; background: var(--surface); border: 1px solid var(--border); border-radius: var(--radius-md); padding: var(--space-4); display: flex; flex-direction: column; gap: var(--space-1); }
.metric--wide { grid-column: span 4; }
.metric__label { font-size: var(--text-sm); color: var(--fg-3); }
.metric__value { font-family: var(--font-mono); font-size: 24px; font-weight: var(--fw-semibold); letter-spacing: -0.01em; color: var(--fg); font-variant-numeric: tabular-nums; }
.metric__sub { font-family: var(--font-mono); font-size: var(--text-xs); color: var(--fg-3); font-variant-numeric: tabular-nums; letter-spacing: 0.01em; }

.dash-grid-2 { display: grid; grid-template-columns: 1fr 1fr; gap: var(--space-4); margin-bottom: var(--space-4); }
.dash-grid-2 + .panel, .dash-grid-2 ~ .panel { margin-bottom: var(--space-4); }

.dash-error { text-align: center; color: var(--fg-2); }
</style>
