/**
 * 展示层格式化与枚举字典。
 * 颜色不在此处，一律在组件的 style 中引用 design-tokens.css 的 CSS 变量。
 */
import type {
  CompanyScale,
  ConfidenceLevel,
  ExtractStatus,
  IntentCategory,
  LeadStatus,
  PriorityLevel,
} from '@/types/lead';
import type { UserRole } from '@/types/user';
import type { ImportItemStatus, ImportTaskStatus } from '@/types/import-task';

function pad(n: number): string {
  return String(n).padStart(2, '0');
}

/** ISO 时间 -> YYYY-MM-DD HH:mm（本地时区）；空值返回占位符 */
export function formatDateTime(value?: string | null): string {
  if (!value) return '—';
  const d = new Date(value);
  if (Number.isNaN(d.getTime())) return value;
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/** ISO 时间 -> YYYY-MM-DD；空值返回占位符 */
export function formatDate(value?: string | null): string {
  if (!value) return '—';
  const d = new Date(value);
  if (Number.isNaN(d.getTime())) return value;
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

/** 金额（元）-> 中文可读；>=1 万按“万”展示 */
export function formatAmount(value?: number | null): string {
  if (value === null || value === undefined) return '—';
  if (value >= 10000) {
    const wan = value / 10000;
    return `${Number.isInteger(wan) ? wan : wan.toFixed(1)} 万`;
  }
  return `${value} 元`;
}

/**
 * 线索的「可识别称谓」= 客户名 · 公司名（去空后拼接）。
 * 统一替代已撤销的「编号前缀」展示格式（team-lead D-5）：用户靠客户名/公司名认出线索，不靠编号；
 * 编号就是数据库主键 `#{id}`，不再包装成第二种身份。
 * 两者皆空时返回空串，由调用方决定回退文案。
 */
export function leadDisplayName(lead: {
  customerName?: string | null;
  companyName?: string | null;
}): string {
  return [lead.customerName?.trim(), lead.companyName?.trim()]
    .filter((value): value is string => Boolean(value))
    .join(' · ');
}

/** 文本占位 */
export function orDash(value?: string | null): string {
  const v = (value ?? '').toString().trim();
  return v ? v : '—';
}

export const STATUS_LABEL: Record<LeadStatus, string> = {
  NEW: '新线索',
  FOLLOWING: '跟进中',
  WON: '已成交',
  INVALID: '无效',
};

export const INTENT_LABEL: Record<IntentCategory, string> = {
  CONSULT: '咨询',
  QUOTE: '报价',
  COOPERATION: '合作',
  COMPLAINT: '投诉',
  IRRELEVANT: '无关',
};

export const PRIORITY_LABEL: Record<PriorityLevel, string> = {
  HIGH: '高',
  MEDIUM: '中',
  LOW: '低',
};

export const EXTRACT_STATUS_LABEL: Record<ExtractStatus, string> = {
  WAITING: '待抽取',
  PROCESSING: '抽取中',
  SUCCESS: '抽取成功',
  FAILED: '解析失败（待人工）',
  DEGRADED: '已降级（待人工）',
};

export const CONFIDENCE_LABEL: Record<ConfidenceLevel, string> = {
  HIGH: '高置信',
  MEDIUM: '待复核',
  LOW: '待复核',
};

export const COMPANY_SCALE_LABEL: Record<CompanyScale, string> = {
  LARGE: '大型',
  MEDIUM: '中型',
  SMALL: '小型',
  UNKNOWN: '未知',
};

export const FOLLOW_TYPE_LABEL: Record<string, string> = {
  NOTE: '备注',
  CALL: '电话',
  EMAIL: '邮件',
  VISIT: '拜访',
  OTHER: '其他',
};

/** 任务五态文案（UIUX §7.5.6，独立于线索四态） */
export const IMPORT_TASK_STATUS_LABEL: Record<ImportTaskStatus, string> = {
  PENDING: '排队中',
  RUNNING: '处理中',
  SUCCESS: '已完成',
  PARTIAL: '部分失败',
  FAILED: '失败',
};

/** 任务逐条结果状态文案（openapi 1.3.1 itemStatus 六值；DEGRADED=线索已创建但 AI 识别降级转人工） */
export const IMPORT_ITEM_STATUS_LABEL: Record<ImportItemStatus, string> = {
  PENDING: '排队中',
  RUNNING: '处理中',
  SUCCESS: '成功',
  DUPLICATE: '跳过',
  FAILED: '失败',
  DEGRADED: '已创建·降级（转人工）',
};

export const ROLE_LABEL: Record<UserRole, string> = {
  SPECIALIST: '销售专员',
  SUPERVISOR: '销售主管',
};

export const IMPORT_SOURCE_LABEL: Record<string, string> = {
  // J-4 v2：SINGLE 覆盖「粘贴导入 + 手工录入」两种模式，标"手工录入"会把粘贴线索标错；
  // 只述条数不述方式，"是否触发 AI"由详情页 WAITING 态注记「手工录入不触发 AI 识别」区分
  SINGLE: '单条录入',
  BATCH: '批量导入',
};
