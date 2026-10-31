/**
 * 图表辅助 —— 读取设计 Token 的实际色值给 ECharts 用（CSS 变量 ECharts 读不到）。
 * 全站图表配色一律走 Token（UIUX §9.3：不用彩虹、每图 ≤6 色）。
 */
export function chartColor(tokenName: string): string {
  const value = getComputedStyle(document.documentElement).getPropertyValue(tokenName).trim();
  return value || '#64748B'; // 兜底 --neutral，正常路径不会走到
}
