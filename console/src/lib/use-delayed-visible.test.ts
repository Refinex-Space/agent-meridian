import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { useDelayedVisible } from './use-delayed-visible';

describe('useDelayedVisible', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it('150ms 内结束的加载不显示骨架', async () => {
    const { result, rerender } = renderHook(({ active }) => useDelayedVisible(active), {
      initialProps: { active: true },
    });
    await act(() => vi.advanceTimersByTimeAsync(140));
    rerender({ active: false });
    await act(() => vi.advanceTimersByTimeAsync(500));
    expect(result.current).toBe(false);
  });

  it('超过 150ms 才显示，显示后至少保持 300ms', async () => {
    const { result, rerender } = renderHook(({ active }) => useDelayedVisible(active), {
      initialProps: { active: true },
    });
    await act(() => vi.advanceTimersByTimeAsync(149));
    expect(result.current).toBe(false);
    await act(() => vi.advanceTimersByTimeAsync(1));
    expect(result.current).toBe(true);

    await act(() => vi.advanceTimersByTimeAsync(100));
    rerender({ active: false });
    await act(() => vi.advanceTimersByTimeAsync(199));
    expect(result.current).toBe(true);
    await act(() => vi.advanceTimersByTimeAsync(1));
    expect(result.current).toBe(false);
  });

  it('显示超过最短时长后，加载结束立即隐藏', async () => {
    const { result, rerender } = renderHook(({ active }) => useDelayedVisible(active), {
      initialProps: { active: true },
    });
    await act(() => vi.advanceTimersByTimeAsync(800));
    expect(result.current).toBe(true);
    rerender({ active: false });
    await act(() => vi.advanceTimersByTimeAsync(0));
    expect(result.current).toBe(false);
  });
});
