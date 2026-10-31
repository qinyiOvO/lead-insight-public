/**
 * 线索相关类型 —— 严格照 openapi.yaml v1.2.1 手写（字段名驼峰、枚举用联合类型）。
 */

/**
 * 意图类别（内容属性）。
 * IRRELEVANT＝无关（广告/垃圾/自动回复），与 LeadStatus.INVALID（跟进结论）语义不同，
 * 两者是两套独立枚举，禁止混用。
 */
export type IntentCategory = 'CONSULT' | 'QUOTE' | 'COOPERATION' | 'COMPLAINT' | 'IRRELEVANT';

/** 线索状态（跟进结论）：新线索/跟进中/已成交/无效 */
export type LeadStatus = 'NEW' | 'FOLLOWING' | 'WON' | 'INVALID';

/** 抽取状态 */
export type ExtractStatus = 'WAITING' | 'PROCESSING' | 'SUCCESS' | 'FAILED' | 'DEGRADED';

/** 优先级档位 */
export type PriorityLevel = 'HIGH' | 'MEDIUM' | 'LOW';

/**
 * 抽取置信度档位（由 extract_confidence 派生，一一对应）：
 * HIGH=0.9 / MEDIUM=0.7 / LOW=0.4。null=未抽取/降级/未评估（仅 extract_status=SUCCESS 时非空）。
 */
export type ConfidenceLevel = 'HIGH' | 'MEDIUM' | 'LOW';

/** 公司规模 */
export type CompanyScale = 'LARGE' | 'MEDIUM' | 'SMALL' | 'UNKNOWN';

/** 线索来源 */
export type ImportSource = 'SINGLE' | 'BATCH';

/** 评分排序字段 */
export type LeadSortField = 'priority_score' | 'created_at';
export type SortOrder = 'asc' | 'desc';

/** openapi LeadVO（v1.2.1：新增 expectedTime / ownerName，字段顺序与 yaml 一致） */
export interface LeadVO {
  id: number;
  subjectTitle: string | null;
  customerName: string | null;
  companyName: string | null;
  intentCategory: IntentCategory | null;
  demandSummary: string | null;
  /** 期望时间（规范化日期）。列表需展示该列，且为紧迫度评分依据 */
  expectedTime?: string | null;
  priorityScore: number;
  priorityLevel: PriorityLevel;
  scoreReason: string | null;
  status: LeadStatus;
  extractStatus: ExtractStatus;
  extractConfidence: number | null;
  confidenceLevel: ConfidenceLevel | null;
  ownerId: number | null;
  /** 负责人姓名，取 sys_user.real_name；未认领为 null（禁止前端只显示「用户 #id」） */
  ownerName?: string | null;
  importSource: ImportSource;
  createdAt: string;
}

/** openapi ScoreDetailVO */
export interface ScoreDetailVO {
  ruleCode: string;
  ruleName: string;
  rawValue: string | null;
  weight: number;
  itemScore: number;
  maxScore: number;
  reason: string | null;
}

/** openapi LeadDetailVO（继承 LeadVO，追加 detail 专有字段） */
export interface LeadDetailVO extends LeadVO {
  originalText: string;
  contactInfo: string | null;
  budgetAmount: number | null;
  budgetText: string | null;
  expectedTime: string | null;
  expectedTimeText: string | null;
  companyScale: string | null;
  keywordsHit: string | null;
  scores: ScoreDetailVO[];
}

/**
 * openapi LeadCreateRequest：一个端点承载两种录入模式。
 * 手工录入模式（M0/R1）：提交 customerName 等任一“手工字段”即判定人工录入，
 * 后端不调用模型，extractStatus 落 WAITING、confidenceLevel 为 null，字段直接生效并立即评分。
 * 两种模式下 originalText 均必填（内容指纹幂等去重依赖该字段）。
 */
export interface LeadCreateRequest {
  subjectTitle?: string;
  originalText: string;
  remark?: string;
  customerName?: string;
  companyName?: string;
  contactInfo?: string;
  intentCategory?: IntentCategory;
  demandSummary?: string;
  budgetAmount?: number;
  expectedTime?: string;
  companyScale?: CompanyScale;
}

/** openapi LeadUpdateRequest：全部字段可选，只提交需要修正的字段 */
export interface LeadUpdateRequest {
  customerName?: string;
  companyName?: string;
  contactInfo?: string;
  intentCategory?: IntentCategory;
  demandSummary?: string;
  budgetAmount?: number;
  expectedTime?: string;
  companyScale?: CompanyScale;
  remark?: string;
}

/** openapi StatusChangeRequest */
export interface StatusChangeRequest {
  toStatus: LeadStatus;
  remark?: string;
}

/** openapi FollowUpCreateRequest */
export type FollowUpType = 'NOTE' | 'CALL' | 'EMAIL' | 'VISIT' | 'OTHER';

export interface FollowUpCreateRequest {
  followType?: FollowUpType;
  content: string;
}

/** openapi FollowUpVO */
export interface FollowUpVO {
  id: number;
  leadId: number;
  userId: number | null;
  followType: string;
  content: string;
  fromStatus: string | null;
  toStatus: string | null;
  createdAt: string;
}

/**
 * GET /api/v1/leads 查询参数（openapi 中 status 为单值参数）。
 * 工作台“NEW+FOLLOWING”由前端做成枚举筛选表达，见 composables/useLeadQuery.ts。
 */
export interface LeadQueryParams {
  page?: number;
  limit?: number;
  sort?: LeadSortField;
  order?: SortOrder;
  status?: LeadStatus;
  intentCategory?: IntentCategory;
  ownerId?: number;
  q?: string;
}
