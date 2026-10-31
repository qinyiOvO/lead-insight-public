<script setup lang="ts">
/**
 * BaseChart —— ECharts 封装（UIUX §9.4）：
 * - echarts/core 按需注册 Pie/Bar/Line/Funnel + Grid/Tooltip/Legend/Title，控制包体；
 * - option 由父组件拼装（配色经 utils/chart.ts 读 Token 实际色值）；
 * - autoresize 跟随容器；loading 走 vue-echarts 内置 showLoading；
 * - 容器 aspect-ratio 由父组件控制，防止 CLS；空态由父组件 v-if 切换（不放本组件内，
 *   因为"有没有数据"父组件最清楚）。
 */
import { use } from 'echarts/core';
import { BarChart, FunnelChart, LineChart, PieChart } from 'echarts/charts';
import {
  GridComponent,
  LegendComponent,
  TitleComponent,
  TooltipComponent,
} from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';
import type { EChartsCoreOption } from 'echarts/core';
import VChart from 'vue-echarts';

use([
  CanvasRenderer,
  PieChart,
  BarChart,
  LineChart,
  FunnelChart,
  GridComponent,
  LegendComponent,
  TooltipComponent,
  TitleComponent,
]);

defineProps<{
  option: EChartsCoreOption;
  loading?: boolean;
  /** 无障碍：图表职责说明（aria-label） */
  label: string;
}>();
</script>

<template>
  <div class="base-chart">
    <VChart
      class="base-chart__canvas"
      :option="option"
      :loading="loading ?? false"
      autoresize
      role="img"
      :aria-label="label"
    />
  </div>
</template>

<style scoped>
.base-chart {
  width: 100%;
  height: 100%;
  min-height: 0;
}

.base-chart__canvas {
  width: 100%;
  height: 100%;
}
</style>
