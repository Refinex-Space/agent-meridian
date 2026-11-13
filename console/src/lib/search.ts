/**
 * 路由的搜索参数格式（ADR-0055）：标准的 `key=value`，多值用重复的键（`?status=running&status=idle`），与 FilterBar 的
 * 取值、`URLSearchParams` 一致，地址可读、可直接分享。TanStack Router 默认把值编码为 JSON，这里替换它的解析与序列化。
 * 搜索参数的值只有字符串与字符串数组；类型与取值由各路由的 `validateSearch` 校验。
 */

export type SearchValue = string | string[];

/** `?a=1&b=x&b=y` → `{ a: '1', b: ['x', 'y'] }`；一个键出现一次是字符串，多次是数组。 */
export function parseSearch(search: string): Record<string, SearchValue> {
  const params = new URLSearchParams(search.startsWith('?') ? search.slice(1) : search);
  const result: Record<string, SearchValue> = {};
  for (const key of new Set(params.keys())) {
    const values = params.getAll(key);
    result[key] = values.length === 1 ? values[0]! : values;
  }
  return result;
}

/** 反向序列化：数组展开为重复的键；只写字符串、数字与布尔值，空串、空数组与其他值不出现在地址中。 */
export function stringifySearch(search: Record<string, unknown>): string {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(search)) {
    const values = Array.isArray(value) ? (value as unknown[]) : [value];
    for (const item of values) {
      if (typeof item === 'string' ? item === '' : typeof item !== 'number' && typeof item !== 'boolean')
        continue;
      params.append(key, String(item));
    }
  }
  const text = params.toString();
  return text ? `?${text}` : '';
}

/** 读一个单值参数（重复出现时取第一个）。 */
export function searchString(value: unknown): string | undefined {
  const first = Array.isArray(value) ? (value as unknown[])[0] : value;
  return typeof first === 'string' && first !== '' ? first : undefined;
}

/** 读一个多值参数。 */
export function searchList(value: unknown): string[] {
  const values = Array.isArray(value) ? (value as unknown[]) : [value];
  return values.filter((v): v is string => typeof v === 'string' && v !== '');
}
