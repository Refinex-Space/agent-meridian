import { useCallback, useEffect, useRef, useState } from 'react';

export type CopyState = 'idle' | 'copied' | 'failed';

/**
 * 一键复制（frontend-design.md「交互规范·复制」）：复制后状态短暂变为 `copied`（图标变对勾），
 * 剪贴板不可用或被拒绝时变为 `failed`，`resetMs` 后回到 `idle`。
 */
export function useCopy(resetMs = 1500) {
  const [state, setState] = useState<CopyState>('idle');
  const timer = useRef<number | undefined>(undefined);

  useEffect(() => () => window.clearTimeout(timer.current), []);

  const copy = useCallback(
    async (text: string) => {
      let next: CopyState = 'copied';
      try {
        if (!navigator.clipboard) throw new Error('剪贴板不可用');
        await navigator.clipboard.writeText(text);
      } catch {
        next = 'failed';
      }
      setState(next);
      window.clearTimeout(timer.current);
      timer.current = window.setTimeout(() => setState('idle'), resetMs);
    },
    [resetMs],
  );

  return { state, copy };
}
