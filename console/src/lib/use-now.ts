import { useSyncExternalStore } from 'react';

/**
 * 全控制台共享的分钟级时钟：相对时间、剩余时间这类显示每 30 秒刷新一次。所有订阅者共用一个定时器，
 * 列表中的几百个 RelativeTime 不会各自起定时器。
 */
const TICK_MS = 30_000;
const listeners = new Set<() => void>();
let now = Date.now();
let timer: number | undefined;

function subscribe(listener: () => void) {
  listeners.add(listener);
  if (timer === undefined) {
    now = Date.now();
    timer = window.setInterval(() => {
      now = Date.now();
      for (const notify of listeners) notify();
    }, TICK_MS);
  }
  return () => {
    listeners.delete(listener);
    if (listeners.size === 0) {
      window.clearInterval(timer);
      timer = undefined;
    }
  };
}

function snapshot() {
  return now;
}

/** 当前时间（毫秒），每 30 秒更新一次。 */
export function useNow(): number {
  return useSyncExternalStore(subscribe, snapshot, snapshot);
}
