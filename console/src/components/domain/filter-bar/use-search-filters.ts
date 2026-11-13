import { useNavigate, useSearch } from '@tanstack/react-router';
import { useCallback, useMemo } from 'react';

import { parseSearch, stringifySearch } from '@/lib/search';

import { parseFilters, serializeFilters, type FilterDef, type FilterValues } from './filters';

/**
 * 筛选条件放在路由的搜索参数里（架构设计「控制台·技术约定」）：取值读自当前地址，修改时以 replace 导航写回（不为每次筛选
 * 堆一条历史记录），只改筛选与搜索的键、保留其他参数；前进后退由路由重新读取。参数格式见 `lib/search.ts`。
 */
export function useSearchFilters(defs: readonly FilterDef[]) {
  // 筛选栏不属于某个具体路由：读当前匹配到的全部搜索参数。
  const search: Record<string, unknown> = useSearch({ strict: false });
  const navigate = useNavigate();
  const text = stringifySearch(search);
  const values = useMemo(() => parseFilters(text, defs), [text, defs]);

  const update = useCallback(
    (next: FilterValues) => {
      const nextSearch = parseSearch(serializeFilters(text, next, defs));
      void navigate({ to: '.', search: nextSearch, replace: true });
    },
    [navigate, text, defs],
  );

  return [values, update] as const;
}
