/**
 * 面包屑的动态名称：路由的 `staticData.crumb` 是静态文字，对象详情要显示对象的名字（"智能体 / support-assistant / 编辑"）。
 * 路由组件在数据到达后调用 `useCrumb(名字)`，外壳按路由匹配的 ID 优先显示它。
 */
import { useMatch } from '@tanstack/react-router';
import { createContext, use, useCallback, useEffect, useMemo, useState, type ReactNode } from 'react';

interface CrumbRegistry {
  labels: Readonly<Record<string, string>>;
  set: (routeId: string, label: string | null) => void;
}

const CrumbContext = createContext<CrumbRegistry | null>(null);

/** 外壳提供：登记各路由匹配的动态名称。 */
export function CrumbProvider({ children }: { children: ReactNode }) {
  const [labels, setLabels] = useState<Readonly<Record<string, string>>>({});
  const set = useCallback((routeId: string, label: string | null) => {
    setLabels((prev) => {
      if ((prev[routeId] ?? null) === label) return prev;
      const next = { ...prev };
      if (label === null) delete next[routeId];
      else next[routeId] = label;
      return next;
    });
  }, []);
  const value = useMemo(() => ({ labels, set }), [labels, set]);
  return <CrumbContext value={value}>{children}</CrumbContext>;
}

/** 各路由匹配的动态名称（路由 ID → 名称）。 */
export function useCrumbLabels(): Readonly<Record<string, string>> {
  return use(CrumbContext)?.labels ?? {};
}

/**
 * 把当前路由在面包屑中的名称换成 `label`（例如智能体的名字）；`label` 为空时保持静态名称。离开路由时撤销。
 * 不在外壳之内（组件测试、故事）时什么也不做。
 */
export function useCrumb(label: string | null | undefined): void {
  const registry = use(CrumbContext);
  const routeId = useMatch({ strict: false, select: (match) => match.routeId });
  const set = registry?.set;
  useEffect(() => {
    if (!set || !routeId || !label) return undefined;
    set(routeId, label);
    return () => set(routeId, null);
  }, [set, routeId, label]);
}
