/**
 * 开发自查用内存 Mock —— 仅当 VITE_USE_MOCK==='true' 且 DEV 时由 api/http.ts 动态载入。
 * 目的：后端尚未就绪时可在浏览器中真实走通登录/列表/详情/增删改查/认领/状态推进/跟进记录的 UI 交互。
 * 不进入生产代码路径；不修改任何真实接口定义。
 *
 * 注意：这里的账号是**假账号**（mock-specialist / mock-supervisor，口令 mock-pass），
 *    与数据库里的真实演示账号无关，故意不写成一样的，避免「读仓库 = 拿到登录口令」。
 *    真实账号口令见《使用手册》（不入库）；后端种子口令由 app.seed.* 配置提供。
 */
import type { AxiosRequestConfig } from 'axios';
import { BizError } from '@/api/error';
import type { PageResult } from '@/types/common';
import type {
  FollowUpVO,
  IntentCategory,
  LeadDetailVO,
  LeadStatus,
  LeadVO,
  ScoreDetailVO,
} from '@/types/lead';
import type { LoginResponse, UserVO } from '@/types/user';
import type {
  ImportTaskItemVO,
  ImportTaskProgressVO,
  ImportTaskVO,
} from '@/types/import-task';
import type { DistributionVO, OverviewVO, TrendVO } from '@/types/dashboard';

const SPECIALIST: UserVO = {
  id: 2,
  username: 'mock-specialist',
  realName: '张伟',
  role: 'SPECIALIST',
};
const SUPERVISOR: UserVO = {
  id: 1,
  username: 'mock-supervisor',
  realName: '李娜',
  role: 'SUPERVISOR',
};

const STATUS_LABEL: Record<LeadStatus, string> = {
  NEW: '新线索',
  FOLLOWING: '跟进中',
  WON: '已成交',
  INVALID: '无效',
};

interface MockScoreSet {
  total: number;
  items: ScoreDetailVO[];
  reason: string;
}

function buildScores(kind: 'quote' | 'consult' | 'complaint'): MockScoreSet {
  if (kind === 'quote') {
    return {
      total: 87,
      reason:
        '意图[报价, +35]；预算[50万以上, +20]；期望时间[7天内, +20]；公司规模[大型, +10]；关键词命中[采购、招标, +4]；合计 87 分，判定为 高 优先级。',
      items: [
        { ruleCode: 'INTENT', ruleName: '意图类别', rawValue: '报价', weight: 40, itemScore: 35, maxScore: 40, reason: '报价意图，明确商业需求' },
        { ruleCode: 'BUDGET', ruleName: '预算区间', rawValue: '50 万以上', weight: 20, itemScore: 20, maxScore: 20, reason: '预算充足' },
        { ruleCode: 'URGENCY', ruleName: '期望时间紧迫度', rawValue: '7 天内', weight: 20, itemScore: 20, maxScore: 20, reason: '交期紧急' },
        { ruleCode: 'COMPANY', ruleName: '公司规模', rawValue: '大型', weight: 10, itemScore: 10, maxScore: 10, reason: '大型企业' },
        { ruleCode: 'KEYWORD', ruleName: '关键词命中', rawValue: '采购、招标', weight: 10, itemScore: 4, maxScore: 10, reason: '命中 2 个业务关键词' },
      ],
    };
  }
  if (kind === 'consult') {
    return {
      total: 62,
      reason:
        '意图[咨询, +20]；预算[10–50万, +15]；期望时间[30天内, +12]；公司规模[中型, +6]；关键词命中[试用, +2]；合计 62 分，判定为 中 优先级。',
      items: [
        { ruleCode: 'INTENT', ruleName: '意图类别', rawValue: '咨询', weight: 40, itemScore: 20, maxScore: 40, reason: '初步咨询，需求尚不明确' },
        { ruleCode: 'BUDGET', ruleName: '预算区间', rawValue: '10–50 万', weight: 20, itemScore: 15, maxScore: 20, reason: '有预算区间' },
        { ruleCode: 'URGENCY', ruleName: '期望时间紧迫度', rawValue: '30 天内', weight: 20, itemScore: 12, maxScore: 20, reason: '一个月内需要' },
        { ruleCode: 'COMPANY', ruleName: '公司规模', rawValue: '中型', weight: 10, itemScore: 6, maxScore: 10, reason: '中型企业' },
        { ruleCode: 'KEYWORD', ruleName: '关键词命中', rawValue: '试用', weight: 10, itemScore: 2, maxScore: 10, reason: '命中 1 个业务关键词' },
      ],
    };
  }
  return {
    total: 15,
    reason: '意图[投诉, +15]；预算[未提及, +0]；期望时间[未提及, +0]；公司规模[未知, +0]；关键词命中[—, +0]；合计 15 分，判定为 低 优先级。',
    items: [
      { ruleCode: 'INTENT', ruleName: '意图类别', rawValue: '投诉', weight: 40, itemScore: 15, maxScore: 40, reason: '投诉类邮件，非新增商机' },
      { ruleCode: 'BUDGET', ruleName: '预算区间', rawValue: null, weight: 20, itemScore: 0, maxScore: 20, reason: '未提及预算' },
      { ruleCode: 'URGENCY', ruleName: '期望时间紧迫度', rawValue: null, weight: 20, itemScore: 0, maxScore: 20, reason: '未提及期望时间' },
      { ruleCode: 'COMPANY', ruleName: '公司规模', rawValue: '未知', weight: 10, itemScore: 0, maxScore: 10, reason: '规模未知' },
      { ruleCode: 'KEYWORD', ruleName: '关键词命中', rawValue: null, weight: 10, itemScore: 0, maxScore: 10, reason: '未命中业务关键词' },
    ],
  };
}

let seq = 2048;

function makeLead(input: {
  customerName: string;
  companyName: string;
  intentCategory: IntentCategory;
  demandSummary: string;
  originalText: string;
  budgetAmount?: number | null;
  expectedTime?: string | null;
  companyScale?: string | null;
  contactInfo?: string | null;
  subjectTitle?: string | null;
  status?: LeadStatus;
  ownerId?: number | null;
  ownerName?: string | null;
  kind: 'quote' | 'consult' | 'complaint';
  createdAt?: string;
}): LeadDetailVO {
  seq += 1;
  const scores = buildScores(input.kind);
  return {
    id: seq,
    subjectTitle: input.subjectTitle ?? null,
    customerName: input.customerName,
    companyName: input.companyName,
    intentCategory: input.intentCategory,
    demandSummary: input.demandSummary,
    priorityScore: scores.total,
    priorityLevel: scores.total >= 70 ? 'HIGH' : scores.total >= 40 ? 'MEDIUM' : 'LOW',
    scoreReason: scores.reason,
    status: input.status ?? 'NEW',
    extractStatus: 'WAITING',
    extractConfidence: null,
    confidenceLevel: null,
    ownerId: input.ownerId ?? null,
    ownerName: input.ownerName ?? (input.ownerId ? SPECIALIST.realName : null),
    importSource: 'SINGLE',
    createdAt: input.createdAt ?? new Date().toISOString(),
    originalText: input.originalText,
    contactInfo: input.contactInfo ?? null,
    budgetAmount: input.budgetAmount ?? null,
    budgetText: null,
    expectedTime: input.expectedTime ?? null,
    expectedTimeText: null,
    companyScale: input.companyScale ?? null,
    keywordsHit: null,
    scores: scores.items,
  };
}

const seeds: LeadDetailVO[] = [
  makeLead({
    customerName: '陈先生',
    companyName: '广州鑫茂五金制品有限公司',
    intentCategory: 'QUOTE',
    demandSummary: '询 304 不锈钢管件报价，需 500 米，月底前要货。',
    originalText:
      '陈先生 您好：\n我们是广州鑫茂五金制品有限公司，主要做不锈钢管件配套。\n现需要 304 不锈钢管件 500 米，希望贵司提供含税到岸报价，月底前要货。\n如可，请附上规格表与最小起订量。',
    budgetAmount: 680000,
    expectedTime: '2026-10-28',
    companyScale: 'LARGE',
    contactInfo: '13800001234',
    subjectTitle: '304 不锈钢管件询价',
    kind: 'quote',
    createdAt: '2026-10-01T09:20:00+08:00',
  }),
  makeLead({
    customerName: '王经理',
    companyName: '深圳恒益电子科技有限公司',
    intentCategory: 'CONSULT',
    demandSummary: '想了解设备租赁与试用方案，先确认参数再走采购流程。',
    originalText:
      '王经理 您好：\n我方为深圳恒益电子科技有限公司，正在评估产线检测设备。\n想先了解租赁与试用方案，并索取技术参数。确认后再进入采购流程。',
    budgetAmount: 320000,
    expectedTime: '2026-11-15',
    companyScale: 'MEDIUM',
    contactInfo: '0755-88886666',
    subjectTitle: '设备租赁与试用咨询',
    kind: 'consult',
    createdAt: '2026-10-01T10:05:00+08:00',
  }),
  makeLead({
    customerName: '东莞盛达贸易',
    companyName: '东莞盛达贸易有限公司',
    intentCategory: 'COMPLAINT',
    demandSummary: '上月批次到货有外观划痕，要求退换并说明处理时效。',
    originalText:
      '你好：\n上月发出的一批货到厂后发现有外观划痕，共 12 箱。\n请尽快安排退换，并告知处理时效与责任划分。',
    companyScale: 'UNKNOWN',
    subjectTitle: '到货外观问题反馈',
    kind: 'complaint',
    createdAt: '2026-09-30T16:40:00+08:00',
  }),
  makeLead({
    customerName: '佛山力创实业',
    companyName: '佛山力创实业集团有限公司',
    intentCategory: 'COOPERATION',
    demandSummary: '希望以渠道代理形式长期合作，覆盖华南区两条产线。',
    originalText:
      '您好：\n我方为佛山力创实业集团，拟以渠道代理形式开展长期合作，覆盖华南区两条产线。\n请提供代理政策与合作框架。',
    budgetAmount: 1500000,
    expectedTime: '2026-10-12',
    companyScale: 'LARGE',
    kind: 'quote',
    createdAt: '2026-09-29T14:10:00+08:00',
  }),
];

let nextFollowUpId = 900;
const followUpStore = new Map<number, FollowUpVO[]>();

function seedFollowUps(): void {
  const first = seeds[0];
  if (!first) return;
  followUpStore.set(first.id, [
    {
      id: (nextFollowUpId += 1),
      leadId: first.id,
      userId: null,
      followType: 'NOTE',
      content: '手工录入线索，系统按规则完成优先级评分。',
      fromStatus: null,
      toStatus: 'NEW',
      createdAt: '2026-10-01T09:20:10+08:00',
    },
  ]);
}
seedFollowUps();

function fail(code: number, message: string): Promise<never> {
  return Promise.reject(new BizError(code, message));
}

function toLeadVO(detail: LeadDetailVO): LeadVO {
  // 仅剥离 LeadDetailVO 专有字段；expectedTime / ownerName 已属 LeadVO（1.2.1），须随列表返回
  const { originalText, contactInfo, budgetAmount, budgetText, expectedTimeText, companyScale, keywordsHit, scores, ...vo } = detail;
  void originalText;
  void contactInfo;
  void budgetAmount;
  void budgetText;
  void expectedTimeText;
  void companyScale;
  void keywordsHit;
  void scores;
  return vo;
}

function requireLead(id: number): LeadDetailVO {
  const found = seeds.find((item) => item.id === id);
  if (!found) throw new BizError(40400, `线索不存在：#${id}`);
  return found;
}

function parsePath(url: string): { path: string; id: number | null; action: string | null } {
  const clean = url.split('?')[0] ?? '';
  const match = /\/api\/v1\/leads\/(\d+)(?:\/([a-z-]+))?/.exec(clean);
  if (match) {
    return {
      path: clean,
      id: Number(match[1]),
      action: match[2] ?? null,
    };
  }
  return { path: clean, id: null, action: null };
}

function filterAndPage(params: Record<string, unknown>): PageResult<LeadVO> {
  const page = Number(params.page ?? 1) || 1;
  const limit = Number(params.limit ?? 20) || 20;
  const status = params.status as LeadStatus | undefined;
  const intentCategory = params.intentCategory as IntentCategory | undefined;
  const ownerId = params.ownerId === undefined ? undefined : Number(params.ownerId);
  const q = ((params.q as string) ?? '').trim().toLowerCase();

  let list = seeds.slice();
  if (status) list = list.filter((item) => item.status === status);
  if (intentCategory) list = list.filter((item) => item.intentCategory === intentCategory);
  if (ownerId !== undefined) list = list.filter((item) => item.ownerId === ownerId);
  if (q) {
    list = list.filter((item) =>
      [item.customerName, item.companyName, item.demandSummary]
        .filter(Boolean)
        .some((field) => String(field).toLowerCase().includes(q)),
    );
  }

  const sortKey = (params.sort as string) ?? 'priority_score';
  const dir = (params.order as string) === 'asc' ? 1 : -1;
  list.sort((a, b) => {
    if (sortKey === 'created_at') {
      return (new Date(a.createdAt).getTime() - new Date(b.createdAt).getTime()) * dir;
    }
    if (a.priorityScore !== b.priorityScore) return (a.priorityScore - b.priorityScore) * dir;
    return new Date(a.createdAt).getTime() - new Date(b.createdAt).getTime();
  });

  const total = list.length;
  const start = (page - 1) * limit;
  const items = list.slice(start, start + limit).map(toLeadVO);
  return { items, total, page, limit, hasMore: start + items.length < total };
}

/** 与真实后端一致的状态机（非法迁移返回 42201） */
const TRANSITIONS: Record<LeadStatus, LeadStatus[]> = {
  NEW: ['FOLLOWING', 'INVALID'],
  FOLLOWING: ['WON', 'INVALID'],
  WON: [],
  INVALID: [],
};

export function mockRequest<T>(config: AxiosRequestConfig): Promise<T> {
  const method = (config.method ?? 'get').toLowerCase();
  const rawUrl = config.url ?? '';
  const params = (config.params ?? {}) as Record<string, unknown>;
  const body = (config.data ?? {}) as Record<string, unknown>;
  const { path, id, action } = parsePath(rawUrl);

  return (async (): Promise<unknown> => {
    if (path === '/api/v1/auth/login') {
      const username = String(body.username ?? '');
      const password = String(body.password ?? '');
      if (username === 'mock-specialist' && password === 'mock-pass') {
        const res: LoginResponse = { token: 'mock-specialist-token', tokenType: 'Bearer', expiresIn: 7200, user: SPECIALIST };
        return res;
      }
      if (username === 'mock-supervisor' && password === 'mock-pass') {
        const res: LoginResponse = { token: 'mock-supervisor-token', tokenType: 'Bearer', expiresIn: 7200, user: SUPERVISOR };
        return res;
      }
      // 与后端口径一致：不区分“用户不存在/密码错误”
      throw new BizError(40100, '账号或密码错误');
    }
    if (path === '/api/v1/auth/logout') return null;
    if (path === '/api/v1/auth/me') return SPECIALIST;

    if (path === '/api/v1/leads') {
      if (method === 'get') return filterAndPage(params);
      if (method === 'post') {
        const originalText = String(body.originalText ?? '');
        const duplicated = seeds.find((item) => item.originalText === originalText);
        if (duplicated) throw new BizError(40901, `该邮件已导入（线索 #${duplicated.id}）`);
        const created = makeLead({
          customerName: String(body.customerName ?? '未命名客户'),
          companyName: String(body.companyName ?? ''),
          intentCategory: (body.intentCategory as IntentCategory) ?? 'CONSULT',
          demandSummary: String(body.demandSummary ?? ''),
          originalText,
          budgetAmount: (body.budgetAmount as number | undefined) ?? null,
          expectedTime: (body.expectedTime as string | undefined) ?? null,
          companyScale: (body.companyScale as string | undefined) ?? null,
          contactInfo: (body.contactInfo as string | undefined) ?? null,
          subjectTitle: (body.subjectTitle as string | undefined) ?? null,
          kind: 'consult',
        });
        seeds.unshift(created);
        return toLeadVO(created);
      }
    }

    if (id !== null) {
      const lead = requireLead(id);
      if (action === null) {
        if (method === 'get') return lead;
        if (method === 'patch') {
          Object.assign(lead, body);
          return toLeadVO(lead);
        }
        if (method === 'delete') {
          const index = seeds.findIndex((item) => item.id === id);
          if (index >= 0) seeds.splice(index, 1);
          return null;
        }
      }
      if (action === 'claim' && method === 'post') {
        if (lead.status !== 'NEW' || lead.ownerId !== null) {
          throw new BizError(42201, '非法状态迁移：该线索已被认领或已进入跟进');
        }
        lead.ownerId = SPECIALIST.id;
        lead.ownerName = SPECIALIST.realName;
        lead.status = 'FOLLOWING';
        return toLeadVO(lead);
      }
      if (action === 'status' && method === 'post') {
        const to = body.toStatus as LeadStatus;
        if (!TRANSITIONS[lead.status].includes(to)) {
          throw new BizError(
            42201,
            `非法状态迁移：不允许从[${STATUS_LABEL[lead.status]}]变更为[${STATUS_LABEL[to]}]`,
          );
        }
        lead.status = to;
        return toLeadVO(lead);
      }
      if (action === 'scores' && method === 'get') return lead.scores;
      if (action === 'follow-ups') {
        const list = followUpStore.get(id) ?? [];
        if (method === 'get') {
          return list.slice().sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime());
        }
        if (method === 'post') {
          const record: FollowUpVO = {
            id: (nextFollowUpId += 1),
            leadId: id,
            userId: SPECIALIST.id,
            followType: String(body.followType ?? 'NOTE'),
            content: String(body.content ?? ''),
            fromStatus: null,
            toStatus: null,
            createdAt: new Date().toISOString(),
          };
          followUpStore.set(id, [...list, record]);
          return record;
        }
      }
    }

    // ---------------- R3：批量导入任务（内存模拟，任务进度按挂钟时间推进） ----------------
    if (rawUrl.startsWith('/api/v1/import-tasks') || rawUrl.startsWith('/api/v1/dashboard')) {
      return mockR3(method, rawUrl, params, body);
    }

    return fail(40400, `未实现的 Mock 路由：${method.toUpperCase()} ${rawUrl}`);
  })() as unknown as Promise<T>;
}

// ---------------- R3 Mock：批量导入任务 + 看板（骨架开发用，非契约真源） ----------------

interface MockTask {
  vo: ImportTaskVO;
  createdAtMs: number;
  texts: string[];
}

const taskStore: MockTask[] = [
  (() => {
    const now = Date.now();
    const iso = (offsetMin: number) => new Date(now - offsetMin * 60_000).toISOString();
    return {
      vo: {
        id: 87, taskNo: 'T-0087', fileName: null, importSource: 'BATCH',
        // DEF-04 三桶+失败：成功9+去重1+转人工1+失败1=12（DEGRADED 计入 manual，不计 fail）
        totalCount: 12, processedCount: 12, successCount: 9, duplicateCount: 1, manualCount: 1, failCount: 1,
        status: 'SUCCESS' as const, startedAt: iso(66), finishedAt: iso(64), createdAt: iso(66),
      },
      createdAtMs: now - 66 * 60_000,
      texts: [],
    };
  })(),
  (() => {
    const now = Date.now();
    const iso = (offsetMin: number) => new Date(now - offsetMin * 60_000).toISOString();
    return {
      vo: {
        id: 86, taskNo: 'T-0086', fileName: '客户邮件20260930.txt', importSource: 'FILE',
        // DEF-04：成功26+去重2+转人工1+失败1=30
        totalCount: 30, processedCount: 30, successCount: 26, duplicateCount: 2, manualCount: 1, failCount: 1,
        status: 'PARTIAL' as const, startedAt: iso(300), finishedAt: iso(295), createdAt: iso(300),
      },
      createdAtMs: now - 300 * 60_000,
      texts: [],
    };
  })(),
];

let nextTaskId = 88;
let nextTaskNo = 88;

/** 进度按挂钟推进：1s 起步 RUNNING，每秒处理 3 条，处理完按 fail/manual 判 SUCCESS/PARTIAL（DEF-04 四桶口径） */
function deriveStatus(task: MockTask): ImportTaskVO['status'] {
  const elapsedSec = (Date.now() - task.createdAtMs) / 1000;
  if (elapsedSec < 1) return 'PENDING';
  const processed = Math.min(task.vo.totalCount, Math.floor(elapsedSec * 3));
  if (processed < task.vo.totalCount) return 'RUNNING';
  return task.vo.failCount > 0 || task.vo.manualCount > 0 ? 'PARTIAL' : 'SUCCESS';
}

function withProgress(task: MockTask): ImportTaskVO {
  const status = deriveStatus(task);
  const done = status === 'SUCCESS' || status === 'PARTIAL';
  const processed = done ? task.vo.totalCount : Math.min(task.vo.totalCount, Math.floor(((Date.now() - task.createdAtMs) / 1000) * 3));
  const success = Math.max(0, processed - Math.ceil(processed * 0.1));
  const dup = Math.min(task.vo.duplicateCount, processed - success);
  const manual = Math.min(task.vo.manualCount, processed - success - dup);
  return {
    ...task.vo,
    status,
    processedCount: processed,
    successCount: done ? task.vo.successCount : success,
    duplicateCount: done ? task.vo.duplicateCount : dup,
    manualCount: done ? task.vo.manualCount : manual,
    failCount: done ? task.vo.failCount : Math.max(0, processed - success - dup - manual),
  };
}

function buildItems(task: MockTask, vo: ImportTaskVO): ImportTaskItemVO[] {
  return task.texts.map((_text, i) => {
    const seq = i + 1;
    if (seq <= vo.processedCount) {
      // DEGRADED 样本（模拟无 key 批量）：倒数第二条降级建档，leadId 有值（openapi 1.3.1）；
      // DEF-04：DEGRADED 计入 manualCount（转人工），FAILED 才计入 failCount
      if (vo.manualCount > 0 && seq === vo.totalCount - 1) return { seq, itemStatus: 'DEGRADED' as const, leadId: 9000 + seq, customerName: 'Mock 客户 ' + seq, reason: 'AI 识别降级，已按原始文本建档，请人工补全' };
      if (seq % 9 === 0) return { seq, itemStatus: 'DUPLICATE' as const, leadId: null, customerName: null, reason: '指纹匹配，已存在同名线索' };
      if (vo.failCount > 0 && seq === vo.totalCount) return { seq, itemStatus: 'FAILED' as const, leadId: null, customerName: null, reason: '文本过短，未抽取到有效字段' };
      return { seq, itemStatus: 'SUCCESS' as const, leadId: 9000 + seq, customerName: 'Mock 客户 ' + seq, reason: null };
    }
    if (vo.status === 'RUNNING' && seq === vo.processedCount + 1) return { seq, itemStatus: 'RUNNING' as const, leadId: null, customerName: null, reason: null };
    return { seq, itemStatus: 'PENDING' as const, leadId: null, customerName: null, reason: null };
  });
}

function pageOf<T>(items: T[], params: Record<string, unknown>): PageResult<T> {
  const page = Number(params.page ?? 1) || 1;
  const limit = Number(params.limit ?? 20) || 20;
  const start = (page - 1) * limit;
  const slice = items.slice(start, start + limit);
  return { items: slice, total: items.length, page, limit, hasMore: start + slice.length < items.length };
}

function mockR3(
  method: string,
  rawUrl: string,
  params: Record<string, unknown>,
  body: Record<string, unknown>,
): unknown {
  const clean = rawUrl.split('?')[0] ?? '';
  const taskMatch = /\/api\/v1\/import-tasks\/(\d+)(?:\/([a-z-]+))?/.exec(clean);

  if (clean === '/api/v1/import-tasks') {
    if (method === 'get') return pageOf(taskStore.map((t) => withProgress(t)), params);
    if (method === 'post') {
      const textList = (body.textList as string[] | undefined) ?? [];
      if (!Array.isArray(textList) || textList.length === 0) throw new BizError(40001, 'textList 不能为空');
      if (textList.length > 200) throw new BizError(40001, '单次最多 200 条，请分批导入');
      const now = Date.now();
      const task: MockTask = {
        vo: {
          id: nextTaskId,
          taskNo: 'T-' + String(nextTaskNo).padStart(4, '0'),
          fileName: body.importSource === 'FILE' ? 'mock-import.txt' : null,
          importSource: String(body.importSource ?? 'BATCH'),
          totalCount: textList.length, processedCount: 0, successCount: 0, duplicateCount: 0, manualCount: 0, failCount: 0,
          status: 'PENDING', startedAt: new Date(now).toISOString(), finishedAt: null,
          createdAt: new Date(now).toISOString(),
        },
        createdAtMs: now,
        texts: textList,
      };
      // DEF-04 四桶口径：末条超短文本计失败（failCount），倒数第二条降级计转人工（manualCount）
      task.vo.failCount = 1;
      task.vo.manualCount = textList.length > 1 ? 1 : 0;
      task.vo.successCount = Math.max(0, textList.length - task.vo.failCount - task.vo.manualCount); // 终态汇总自洽（明细和=总数）
      nextTaskId += 1;
      nextTaskNo += 1;
      taskStore.unshift(task);
      return withProgress(task);
    }
  }

  if (taskMatch) {
    const id = Number(taskMatch[1]);
    const task = taskStore.find((t) => t.vo.id === id);
    if (!task) throw new BizError(40400, '任务不存在');
    const action = taskMatch[2] ?? null;
    const vo = withProgress(task);
    if (action === null && method === 'get') return vo;
    if (action === 'progress' && method === 'get') {
      const progress: ImportTaskProgressVO = {
        taskId: vo.id, status: vo.status, total: vo.totalCount, processed: vo.processedCount,
        success: vo.successCount, duplicate: vo.duplicateCount, manual: vo.manualCount, fail: vo.failCount,
        percent: Math.round((vo.processedCount / Math.max(1, vo.totalCount)) * 100),
      };
      return progress;
    }
    if (action === 'items' && method === 'get') return buildItems(task, vo);
    if (action === 'leads' && method === 'get') return pageOf<LeadVO>([], params);
  }

  if (clean === '/api/v1/dashboard/overview' && method === 'get') {
    const overview: OverviewVO = {
      totalLeads: 1284, invalidLeads: 96, wonLeads: 236, newCount: 640,
      followingCount: 640, pendingManualCount: 18, conversionRate: 0.199,
    };
    return overview;
  }
  if (clean === '/api/v1/dashboard/status-distribution' && method === 'get') {
    const list: DistributionVO[] = [
      { key: 'NEW', label: '新线索', value: 408 },
      { key: 'FOLLOWING', label: '跟进中', value: 640 },
      { key: 'WON', label: '已成交', value: 236 },
      { key: 'INVALID', label: '无效', value: 96 },
    ];
    return list;
  }
  if (clean === '/api/v1/dashboard/category-distribution' && method === 'get') {
    const list: DistributionVO[] = [
      { key: 'QUOTE', label: '报价', value: 512 },
      { key: 'CONSULT', label: '咨询', value: 433 },
      { key: 'COOPERATION', label: '合作', value: 187 },
      { key: 'COMPLAINT', label: '投诉', value: 84 },
      { key: 'IRRELEVANT', label: '无关', value: 68 },
    ];
    return list;
  }
  if (clean === '/api/v1/dashboard/trend' && method === 'get') {
    const days = Math.min(90, Math.max(1, Number(params.days ?? 30) || 30));
    const list: TrendVO[] = [];
    for (let i = days - 1; i >= 0; i -= 1) {
      const d = new Date(Date.now() - i * 86_400_000);
      const date = d.toISOString().slice(0, 10);
      const base = 18 + Math.round(10 * Math.sin((days - i) / 4.2));
      list.push({ date, createdCount: base + (i % 5), wonCount: Math.max(1, Math.round(base * 0.2)) });
    }
    return list;
  }

  return fail(40400, '未实现的 Mock 路由：' + method.toUpperCase() + ' ' + rawUrl);
}
