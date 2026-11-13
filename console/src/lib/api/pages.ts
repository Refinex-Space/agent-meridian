/** 游标分页的一页（console-api-map.md「约定·分页」）。 */
export interface CursorPage<T> {
  data: T[];
  has_more: boolean;
  next_cursor: string | null;
}

/** 读完全部页的上限：项目内的智能体、环境、技能等是几十个的量级，超过时只取前这么多页。 */
const MAX_PAGES = 20;

/**
 * 按游标读完一个列表（每页 100 条）。用于选择器与小列表：项目内的智能体、环境、模型目录等。
 *
 * @example
 * const agents = await collectPages((after) =>
 *   unwrap(adminApi().GET(path, { params: { path: ids, query: { limit: 100, ...(after ? { after } : {}) } }, signal })),
 * );
 */
export async function collectPages<T>(
  fetchPage: (after: string | undefined) => Promise<CursorPage<T>>,
): Promise<T[]> {
  const items: T[] = [];
  let after: string | undefined;
  for (let i = 0; i < MAX_PAGES; i++) {
    const page = await fetchPage(after);
    items.push(...page.data);
    if (!page.has_more || !page.next_cursor) break;
    after = page.next_cursor;
  }
  return items;
}

/** 读完全部页时每页的条数（规范的上限）。 */
export const PAGE_LIMIT = 100;
