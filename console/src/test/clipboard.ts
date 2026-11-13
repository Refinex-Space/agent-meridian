import { vi } from 'vitest';

/** 在 jsdom 中装一个可断言的剪贴板；`available: false` 模拟非安全上下文（没有 navigator.clipboard）。 */
export function mockClipboard({ available = true }: { available?: boolean } = {}) {
  const writeText = vi.fn<(text: string) => Promise<void>>().mockResolvedValue(undefined);
  Object.defineProperty(navigator, 'clipboard', {
    configurable: true,
    value: available ? { writeText } : undefined,
  });
  return writeText;
}
