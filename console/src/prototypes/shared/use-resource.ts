import { useCallback, useEffect, useEffectEvent, useState } from 'react';

export interface Resource<T> {
  data: T | null;
  error: unknown;
  loading: boolean;
  /** 重新请求（重试、筛选变化之外的手动刷新）。 */
  reload: () => void;
}

/**
 * 原型里的数据加载：请求一次，`key` 变化时重新请求并取消上一次。T21 换成 TanStack Query（缓存、重试、轮询）。
 */
export function useResource<T>(load: (signal: AbortSignal) => Promise<T>, key: string): Resource<T> {
  const [state, setState] = useState<{ key: string; data: T | null; error: unknown }>({
    key: '',
    data: null,
    error: null,
  });
  const [attempt, setAttempt] = useState(0);
  // 调用方每次渲染都给新的 load；只有 key 与重试次数变化时才重新请求。
  const run = useEffectEvent((signal: AbortSignal) => load(signal));

  useEffect(() => {
    const controller = new AbortController();
    run(controller.signal)
      .then((data) => setState({ key, data, error: null }))
      .catch((error: unknown) => {
        if (!controller.signal.aborted) setState({ key, data: null, error });
      });
    return () => controller.abort();
  }, [key, attempt]);

  const reload = useCallback(() => {
    setState((s) => ({ ...s, key: '' }));
    setAttempt((n) => n + 1);
  }, []);

  const fresh = state.key === key;
  return { data: fresh ? state.data : null, error: fresh ? state.error : null, loading: !fresh, reload };
}

/** 页面可见时每 `ms` 毫秒调用一次 `tick`（列表的轮询）；隐藏时停止。 */
export function useInterval(tick: () => void, ms: number | null) {
  const onTick = useEffectEvent(tick);
  useEffect(() => {
    if (ms === null) return undefined;
    const timer = window.setInterval(() => {
      if (document.visibilityState === 'visible') onTick();
    }, ms);
    return () => window.clearInterval(timer);
  }, [ms]);
}
