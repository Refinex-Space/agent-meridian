import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { initTheme, setThemePreference, THEME_KEY, useThemePreference } from './theme';

/** 可切换的 prefers-color-scheme。 */
function mockSystemTheme(dark: boolean) {
  const listeners = new Set<() => void>();
  const query = {
    get matches() {
      return dark;
    },
    addEventListener: (_: string, listener: () => void) => listeners.add(listener),
  };
  vi.spyOn(window, 'matchMedia').mockReturnValue(query as unknown as MediaQueryList);
  return (next: boolean) => {
    dark = next;
    for (const listener of listeners) listener();
  };
}

const theme = () => document.documentElement.dataset['theme'];

describe('主题', () => {
  beforeEach(() => {
    window.localStorage.clear();
  });
  afterEach(() => {
    window.localStorage.clear();
  });

  it('没有设置时跟随系统，系统切换时随之变化', () => {
    const setSystem = mockSystemTheme(true);
    initTheme();
    expect(theme()).toBe('dark');
    setSystem(false);
    expect(theme()).toBe('light');
  });

  it('选择亮色或暗色后记在本地，不再随系统变化；改回跟随系统时删除设置', () => {
    const setSystem = mockSystemTheme(false);
    initTheme();
    const { result } = renderHook(() => useThemePreference());
    expect(result.current).toBe('system');

    act(() => setThemePreference('dark'));
    expect(result.current).toBe('dark');
    expect(theme()).toBe('dark');
    expect(window.localStorage.getItem(THEME_KEY)).toBe('dark');
    setSystem(false);
    expect(theme()).toBe('dark');

    act(() => setThemePreference('system'));
    expect(window.localStorage.getItem(THEME_KEY)).toBeNull();
    expect(theme()).toBe('light');
  });

  it('启动时读出本地的设置', () => {
    mockSystemTheme(true);
    window.localStorage.setItem(THEME_KEY, 'light');
    initTheme();
    expect(theme()).toBe('light');
  });
});
