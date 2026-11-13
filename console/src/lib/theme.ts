import { useSyncExternalStore } from 'react';

/** 主题设置：亮色、暗色或跟随系统，记在本地（每个浏览器一份）。 */
export type ThemePreference = 'light' | 'dark' | 'system';
export type Theme = 'light' | 'dark';

/** 与 index.html 中首次绘制前的脚本使用同一个键。 */
export const THEME_KEY = 'meridian.theme';

const DARK_QUERY = '(prefers-color-scheme: dark)';

function read(): ThemePreference {
  try {
    const value = window.localStorage.getItem(THEME_KEY);
    return value === 'light' || value === 'dark' ? value : 'system';
  } catch {
    return 'system';
  }
}

let preference: ThemePreference = 'system';
const listeners = new Set<() => void>();

function resolve(pref: ThemePreference): Theme {
  if (pref !== 'system') return pref;
  return window.matchMedia(DARK_QUERY).matches ? 'dark' : 'light';
}

function apply(): void {
  document.documentElement.dataset['theme'] = resolve(preference);
}

/**
 * 启动时调用一次：读出设置并写到 `<html data-theme>`，之后在"跟随系统"时随系统切换。index.html 的内联脚本已在首次绘制前
 * 写过一次，这里接管后续的变化。
 */
export function initTheme(): void {
  preference = read();
  apply();
  window.matchMedia(DARK_QUERY).addEventListener('change', () => {
    if (preference === 'system') apply();
  });
}

export function setThemePreference(next: ThemePreference): void {
  preference = next;
  try {
    if (next === 'system') window.localStorage.removeItem(THEME_KEY);
    else window.localStorage.setItem(THEME_KEY, next);
  } catch {
    // 无痕模式或禁用了存储：设置只在本次有效。
  }
  apply();
  for (const listener of listeners) listener();
}

/** 当前的主题设置（用户菜单里的选项）。 */
export function useThemePreference(): ThemePreference {
  return useSyncExternalStore(
    (listener) => {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    () => preference,
  );
}
