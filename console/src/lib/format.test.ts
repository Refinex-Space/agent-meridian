import { describe, expect, it } from 'vitest';

import {
  formatDateTime,
  formatBytes,
  formatDuration,
  formatInteger,
  formatMoney,
  formatRelative,
  formatTokens,
  middleEllipsis,
} from './format';

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

  it('详情子表格的绝对时间到分钟（东八区）', () => {
    expect(formatDateTime('2026-11-13T02:02:14.345Z')).toBe('2026-11-13 10:02');
  });

  it('千分位与字节', () => {
    expect(formatInteger(12480)).toBe('12,480');
    expect(formatBytes(512)).toBe('512 B');
    expect(formatBytes(12_700)).toBe('12.4 KB');
    expect(formatBytes(3_250_000)).toBe('3.1 MB');
  });

  it('中间省略的 ID 保留前缀与末尾', () => {
    expect(middleEllipsis('ses_01JC8Z4V7QX3M9N2P8R5')).toBe('ses_01JC8Z…N2P8R5');
    expect(middleEllipsis('agt_01JB7Q2M9X')).toBe('agt_01JB7Q2M9X');
  });

  it('相对时间按量级换单位，一周以上显示日期', () => {
    const now = Date.parse('2026-11-13T10:00:00+08:00');
    expect(formatRelative('2026-11-13T09:59:30+08:00', now)).toBe('刚刚');
    expect(formatRelative('2026-11-13T09:55:00+08:00', now)).toBe('5 分钟前');
    expect(formatRelative('2026-11-13T07:00:00+08:00', now)).toBe('3 小时前');
    expect(formatRelative('2026-11-11T10:00:00+08:00', now)).toBe('2 天前');
    expect(formatRelative('2026-11-13T10:20:00+08:00', now)).toBe('20 分钟后');
    expect(formatRelative('2026-10-02T16:40:00+08:00', now)).toBe('10 月 2 日 16:40');
    expect(formatRelative('2025-12-30T16:40:00+08:00', now)).toBe('2025 年 12 月 30 日');
  });

  it('金额保留两位小数并标明币种，不足一分不显示为 0', () => {
    expect(formatMoney(1.237, 'CNY')).toBe('¥1.24');
    expect(formatMoney(0.38, 'USD')).toBe('US$0.38');
    expect(formatMoney(0.0004, 'CNY')).toBe('不足 ¥0.01');
    expect(formatMoney(0, 'CNY')).toBe('¥0.00');
  });
});
