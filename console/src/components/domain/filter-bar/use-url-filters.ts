import { useCallback, useEffect, useState } from 'react';

import { parseFilters, serializeFilters, type FilterDef, type FilterValues } from './filters';

/**
 * 筛选条件与 URL 同步：初始值读自地址栏，修改用 replaceState 写回（不为每次筛选堆一条历史记录），
 * 前进后退（popstate）时重新读取。T21 接入 TanStack Router 后，由路由的搜索参数替换这里的 History API。
 */
export function useUrlFilters(defs: readonly FilterDef[]) {
  const [values, setValues] = useState<FilterValues>(() => parseFilters(window.location.search, defs));

  useEffect(() => {
    const onPop = () => setValues(parseFilters(window.location.search, defs));
    window.addEventListener('popstate', onPop);
    return () => window.removeEventListener('popstate', onPop);
  }, [defs]);

  const update = useCallback(
    (next: FilterValues) => {
      setValues(next);
      const search = serializeFilters(window.location.search, next, defs);
      const url = `${window.location.pathname}${search}${window.location.hash}`;
      window.history.replaceState(window.history.state, '', url);
    },
    [defs],
  );

  return [values, update] as const;
}
