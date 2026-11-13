/**
 * 筛选条件的定义与取值。取值就是 URL 搜索参数：多选用重复的键（`?status=running&status=idle`），
 * 搜索词用 `q`；不认识的取值丢弃，不让手改的 URL 产生无效请求。
 */

export interface FilterOption {
  value: string;
  label: string;
}

export interface FilterDef {
  /** URL 参数名，也是请求参数名（`status`、`agent_id`、`created_within`）。 */
  key: string;
  /** 触发按钮上的名称（"状态""智能体"）。 */
  label: string;
  options: readonly FilterOption[];
  /** 多选（状态）或单选（时间范围）。 */
  multiple?: boolean;
}

/** 每个筛选条件选中的值（单选也用数组，最多一个），加上搜索词。 */
export interface FilterValues {
  q: string;
  filters: Readonly<Record<string, readonly string[]>>;
}

export const SEARCH_KEY = 'q';

export function emptyValues(defs: readonly FilterDef[]): FilterValues {
  return { q: '', filters: Object.fromEntries(defs.map((d) => [d.key, []])) };
}

/** 从 URL 搜索参数读出取值；丢弃不在选项中的值，单选只取第一个。 */
export function parseFilters(search: string, defs: readonly FilterDef[]): FilterValues {
  const params = new URLSearchParams(search);
  const filters: Record<string, string[]> = {};
  for (const def of defs) {
    const allowed = new Set(def.options.map((o) => o.value));
    const values = params.getAll(def.key).filter((v) => allowed.has(v));
    filters[def.key] = def.multiple ? [...new Set(values)] : values.slice(0, 1);
  }
  return { q: params.get(SEARCH_KEY)?.trim() ?? '', filters };
}

/** 把取值写回搜索参数：只改筛选与搜索的键，保留其他参数（页码以外的视图状态）。 */
export function serializeFilters(search: string, values: FilterValues, defs: readonly FilterDef[]): string {
  const params = new URLSearchParams(search);
  for (const def of defs) {
    params.delete(def.key);
    for (const v of values.filters[def.key] ?? []) params.append(def.key, v);
  }
  params.delete(SEARCH_KEY);
  if (values.q) params.set(SEARCH_KEY, values.q);
  const text = params.toString();
  return text ? `?${text}` : '';
}

export function hasActiveFilters(values: FilterValues): boolean {
  return values.q !== '' || Object.values(values.filters).some((v) => v.length > 0);
}
