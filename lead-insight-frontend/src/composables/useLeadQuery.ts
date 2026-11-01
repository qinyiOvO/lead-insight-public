/**
 * useLeadQuery —— 线索查询组合式函数（WorkbenchView 与 LeadListView 共用，禁止复制）。
 *
 * 职责：查询状态 + URL query 同步（刷新 / 前进后退可复现，AC-12）+ 请求 + 分页。
 * 两视图差异只通过 options 表达：
 * - scope: 'mine'  工作台：ownerId 固定为当前用户（不在 URL 中暴露）
 * - scope: 'all'   全量列表
 *
 * 关于「默认 NEW+FOLLOWING」：openapi 的 status 为单值参数，无法一次传两个值。
 * 因此工作台把状态做成枚举筛选（全部 / 新线索 / 跟进中），默认选中「全部」且不传 status，
 * 由 ownerId 收敛出「我的」范围。这是如实采用的方案（见交付报告）。
 */
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import type { LocationQuery } from 'vue-router';
import { listLeads } from '@/api/lead';
import type { IntentCategory, LeadStatus, LeadSortField, LeadVO, SortOrder } from '@/types/lead';
import { useUserStore } from '@/stores/user';

export interface UseLeadQueryOptions {
  scope: 'mine' | 'all';
  defaultSort?: LeadSortField;
  defaultOrder?: SortOrder;
}

export interface StatusOption {
  label: string;
  value: LeadStatus | '';
}

const STATUS_VALUES: LeadStatus[] = ['NEW', 'FOLLOWING', 'WON', 'INVALID'];
const CATEGORY_VALUES: IntentCategory[] = ['CONSULT', 'QUOTE', 'COOPERATION', 'COMPLAINT', 'IRRELEVANT'];
/** 排序白名单（与 openapi / LeadQueryRequest 的枚举一致，非法值一律回落默认） */
const SORT_VALUES: LeadSortField[] = ['priority_score', 'created_at'];
const ORDER_VALUES: SortOrder[] = ['asc', 'desc'];

/**
 * 后端 `GET /api/v1/leads` 的**缺省排序**（openapi `sort.default: priority_score`、`order.default: desc`，
 * 与 `LeadQueryRequest` 字段初值一致）。
 *
 * 供 {@link useLeadQuery} 判断"能否省掉排序参数"：只有请求值**正好等于后端缺省**时省略，
 * 因为此时省与不省的 SQL 完全相同。**不能拿"本页默认"当判据**——本页默认与后端缺省不同时
 * （列表页 `created_at`），省略就等于把排序决定权交回后端缺省，排序意图被静默丢弃（D-01 的成因）。
 */
const BACKEND_DEFAULT_SORT: LeadSortField = 'priority_score';
const BACKEND_DEFAULT_ORDER: SortOrder = 'desc';

function readString(value: unknown): string {
  if (Array.isArray(value)) return typeof value[0] === 'string' ? value[0] : '';
  return typeof value === 'string' ? value : '';
}

function readNumber(value: unknown, fallback: number): number {
  const n = Number(readString(value));
  return Number.isFinite(n) && n > 0 ? Math.floor(n) : fallback;
}

export function useLeadQuery(options: UseLeadQueryOptions) {
  const route = useRoute();
  const router = useRouter();
  const userStore = useUserStore();

  const currentPath = route.path;
  const defaultSort: LeadSortField = options.defaultSort ?? 'priority_score';
  const defaultOrder: SortOrder = options.defaultOrder ?? 'desc';

  const rows = ref<LeadVO[]>([]);
  const total = ref(0);
  const loading = ref(false);
  const error = ref(false);

  const query = reactive({
    page: 1,
    limit: 20,
    sort: defaultSort as LeadSortField,
    order: defaultOrder as SortOrder,
    status: '' as LeadStatus | '',
    intentCategory: '' as IntentCategory | '',
    q: '',
  });

  const statusOptions = computed<StatusOption[]>(() =>
    options.scope === 'mine'
      ? [
          { label: '全部', value: '' },
          { label: '新线索', value: 'NEW' },
          { label: '跟进中', value: 'FOLLOWING' },
        ]
      : [
          { label: '全部', value: '' },
          { label: '新线索', value: 'NEW' },
          { label: '跟进中', value: 'FOLLOWING' },
          { label: '已成交', value: 'WON' },
          { label: '无效', value: 'INVALID' },
        ],
  );

  const categoryOptions: { label: string; value: IntentCategory | '' }[] = [
    { label: '全部', value: '' },
    { label: '咨询', value: 'CONSULT' },
    { label: '报价', value: 'QUOTE' },
    { label: '合作', value: 'COOPERATION' },
    { label: '投诉', value: 'COMPLAINT' },
    { label: '无关', value: 'IRRELEVANT' },
  ];

  const isEmpty = computed(() => !loading.value && !error.value && rows.value.length === 0);

  function serializeQuery(): Record<string, string> {
    const out: Record<string, string> = {
      page: String(query.page),
      limit: String(query.limit),
    };
    const keyword = query.q.trim();
    if (keyword) out.q = keyword;
    if (query.status) out.status = query.status;
    if (query.intentCategory) out.intentCategory = query.intentCategory;
    // URL 洁净度口径（与 fetchList 的省参数口径**不同**，别合并）：
    // 这里比的是"本页默认"，即用户未主动改过排序时 URL 不留冗余参数；
    // fetchList 比的是"后端缺省"，因为那里只关心省掉后 SQL 会不会变。
    if (query.sort !== defaultSort) out.sort = query.sort;
    if (query.order !== defaultOrder) out.order = query.order;
    return out;
  }

  function applyFromRoute(source: LocationQuery): void {
    query.page = readNumber(source.page, 1);
    query.limit = readNumber(source.limit, 20);

    const status = readString(source.status);
    query.status = STATUS_VALUES.includes(status as LeadStatus) ? (status as LeadStatus) : '';

    const category = readString(source.intentCategory);
    query.intentCategory = CATEGORY_VALUES.includes(category as IntentCategory)
      ? (category as IntentCategory)
      : '';

    query.q = readString(source.q);
    // D-01：必须显式识别两个合法值，不能只认 'created_at'——
    // 在 defaultSort='created_at' 的列表页上，URL 里 sort=priority_score（用户点了"优先级"表头）
    // 若被判为非法而回落默认，刷新/前进后退会静默丢掉用户的排序选择。
    const sort = readString(source.sort);
    query.sort = SORT_VALUES.includes(sort as LeadSortField) ? (sort as LeadSortField) : defaultSort;
    const order = readString(source.order);
    query.order = ORDER_VALUES.includes(order as SortOrder) ? (order as SortOrder) : defaultOrder;
  }

  function urlMatches(source: LocationQuery): boolean {
    const serialized = serializeQuery();
    const keys = Object.keys(serialized);
    const sourceKeys = Object.keys(source);
    if (keys.length !== sourceKeys.length) return false;
    return keys.every((key) => readString(source[key]) === serialized[key]);
  }

  async function pushQueryToUrl(): Promise<void> {
    await router.replace({ query: serializeQuery() });
  }

  async function fetchList(): Promise<void> {
    loading.value = true;
    error.value = false;
    try {
      // D-01（2026-11-01 关闭）：省参数的判据由"等于本页默认"改为"等于后端缺省"，且 sort/order 成对判断。
      // 原 F-2 规则拿本页默认做判据，列表页（defaultSort=created_at）的排序意图因此永远到不了后端：
      // 后端 sort 缺省是 priority_score，"省参数"= 让后端按优先级排，与页面声明不符，
      // 且 B2 重建的 idx_lead_created_at 在默认路径上无人消费。
      // 改为对后端缺省后两页各自正确：/leads 默认下发 sort=created_at&order=desc（页面口径）；
      // /workbench 默认正好等于后端缺省 → 不传，AC-18 三层决胜仍由后端保证。
      // URL 洁净度不在此处管——serializeQuery 仍按"本页默认"决定写不写 URL，两件事职责分开。
      const omitSort =
        query.sort === BACKEND_DEFAULT_SORT && query.order === BACKEND_DEFAULT_ORDER;
      const result = await listLeads({
        page: query.page,
        limit: query.limit,
        sort: omitSort ? undefined : query.sort,
        order: omitSort ? undefined : query.order,
        status: query.status || undefined,
        intentCategory: query.intentCategory || undefined,
        q: query.q.trim() || undefined,
        ownerId: options.scope === 'mine' ? (userStore.currentUserId ?? undefined) : undefined,
      });
      rows.value = result.items ?? [];
      total.value = result.total ?? 0;
    } catch {
      rows.value = [];
      total.value = 0;
      error.value = true;
    } finally {
      loading.value = false;
    }
  }

  /** 筛选条件变化：回到第 1 页后刷新，并同步 URL */
  async function search(): Promise<void> {
    query.page = 1;
    await pushQueryToUrl();
    await fetchList();
  }

  async function reset(): Promise<void> {
    query.page = 1;
    query.q = '';
    query.status = '';
    query.intentCategory = '';
    query.sort = defaultSort;
    query.order = defaultOrder;
    await pushQueryToUrl();
    await fetchList();
  }

  async function changePage(page: number): Promise<void> {
    query.page = page;
    await pushQueryToUrl();
    await fetchList();
  }

  async function changeSize(limit: number): Promise<void> {
    query.limit = limit;
    query.page = 1;
    await pushQueryToUrl();
    await fetchList();
  }

  async function changeSort(sort: LeadSortField, order: SortOrder): Promise<void> {
    query.sort = sort;
    query.order = order;
    query.page = 1;
    await pushQueryToUrl();
    await fetchList();
  }

  // 前进/后退：URL 变化时还原筛选与分页（AC-12）
  watch(
    () => route.query,
    (next) => {
      if (route.path !== currentPath) return;
      if (urlMatches(next)) return;
      applyFromRoute(next);
      void fetchList();
    },
  );

  onMounted(async () => {
    applyFromRoute(route.query);
    await fetchList();
    if (!urlMatches(route.query)) {
      await pushQueryToUrl();
    }
  });

  return {
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
  };
}
