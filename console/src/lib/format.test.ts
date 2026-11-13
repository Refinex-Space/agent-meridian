import { describe, expect, it } from 'vitest';

import { formatBytes, formatDuration, formatInteger, formatTokens } from './format';

describe('format', () => {
  it('token 缩写：千以下原样，千用 K，百万用 M', () => {
    expect(formatTokens(412)).toBe('412');
    expect(formatTokens(3240)).toBe('3.2K');
    expect(formatTokens(3000)).toBe('3K');
    expect(formatTokens(48_210)).toBe('48K');
    expect(formatTokens(1_250_000)).toBe('1.25M');
    expect(formatTokens(2_000_000)).toBe('2M');
  });

  it('时长按量级换单位', () => {
    expect(formatDuration(640)).toBe('640 毫秒');
    expect(formatDuration(1600)).toBe('1.6 秒');
    expect(formatDuration(2000)).toBe('2 秒');
    expect(formatDuration(42_300)).toBe('42 秒');
    expect(formatDuration(252_000)).toBe('4 分 12 秒');
    expect(formatDuration(120_000)).toBe('2 分');
    expect(formatDuration(7_500_000)).toBe('2 小时 5 分');
  });

  it('千分位与字节', () => {
    expect(formatInteger(12480)).toBe('12,480');
    expect(formatBytes(512)).toBe('512 B');
    expect(formatBytes(12_700)).toBe('12.4 KB');
    expect(formatBytes(3_250_000)).toBe('3.1 MB');
  });
});
