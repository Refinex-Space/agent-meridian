import { useEffect, useRef, useState } from 'react';

export interface DelayedVisibleOptions {
  /** 加载开始后多久才显示骨架，默认 150ms：更快返回的请求不显示骨架。 */
  delay?: number;
  /** 骨架一旦显示，至少保持多久，默认 300ms，避免闪烁。 */
  minDuration?: number;
}

/**
 * 骨架的显示时机（frontend-design.md「加载」）：`active` 变为 true 后延迟 `delay` 才显示；
 * 显示后即使 `active` 已变为 false，也至少保持 `minDuration`。
 */
export function useDelayedVisible(
  active: boolean,
  { delay = 150, minDuration = 300 }: DelayedVisibleOptions = {},
) {
  const [visible, setVisible] = useState(false);
  const shownAt = useRef<number | null>(null);

  useEffect(() => {
    if (active) {
      if (visible) return undefined;
      const timer = window.setTimeout(() => {
        shownAt.current = Date.now();
        setVisible(true);
      }, delay);
      return () => window.clearTimeout(timer);
    }
    if (!visible) return undefined;
    const elapsed = Date.now() - (shownAt.current ?? 0);
    const timer = window.setTimeout(
      () => {
        shownAt.current = null;
        setVisible(false);
      },
      Math.max(0, minDuration - elapsed),
    );
    return () => window.clearTimeout(timer);
  }, [active, visible, delay, minDuration]);

  return visible;
}
