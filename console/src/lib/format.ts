/**
 * 数字、时长与时间的显示格式（frontend-design.md「交互规范」）：千分位；token 用 K、M 缩写，精确值放在提示里；
 * 时长带中文单位；详情中显示绝对时间。
 */

const integer = new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 0 });

/** 千分位整数：12,480。 */
export function formatInteger(value: number): string {
  return integer.format(value);
}

/** token 缩写：412、3.2K、1.25M；小于 1000 时原样显示。 */
export function formatTokens(value: number): string {
  if (value < 1000) return String(value);
  if (value < 1_000_000) return `${trimZero((value / 1000).toFixed(value < 10_000 ? 1 : 0))}K`;
  return `${trimZero((value / 1_000_000).toFixed(2))}M`;
}

function trimZero(text: string): string {
  return text.replace(/\.0+$/, '').replace(/(\.\d*?)0+$/, '$1');
}

/** 时长：640 毫秒、1.6 秒、42 秒、4 分 12 秒、2 小时 5 分。 */
export function formatDuration(ms: number): string {
  if (ms < 1000) return `${Math.max(0, Math.round(ms))} 毫秒`;
  const seconds = ms / 1000;
  if (seconds < 10) return `${trimZero(seconds.toFixed(1))} 秒`;
  if (seconds < 60) return `${Math.round(seconds)} 秒`;
  const totalSeconds = Math.round(seconds);
  const minutes = Math.floor(totalSeconds / 60);
  if (minutes < 60) {
    const rest = totalSeconds % 60;
    return rest === 0 ? `${minutes} 分` : `${minutes} 分 ${rest} 秒`;
  }
  const hours = Math.floor(minutes / 60);
  const restMinutes = minutes % 60;
  return restMinutes === 0 ? `${hours} 小时` : `${hours} 小时 ${restMinutes} 分`;
}

/** 两个 ISO 时间之间的毫秒数。 */
export function between(from: string, to: string): number {
  return Date.parse(to) - Date.parse(from);
}

const clock = new Intl.DateTimeFormat('zh-CN', {
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
  hourCycle: 'h23',
});

/** 时间轴上的时刻：10:02:14（本地时区）。 */
export function formatClock(iso: string): string {
  return clock.format(new Date(iso));
}

/** 检查器中的绝对时间：2026-11-13 10:02:14.345 UTC+08:00（本地时区，带毫秒与时区）。 */
export function formatInstant(iso: string): string {
  const date = new Date(iso);
  const pad = (n: number, width = 2) => String(n).padStart(width, '0');
  const offset = -date.getTimezoneOffset();
  const sign = offset >= 0 ? '+' : '-';
  const zone = `UTC${sign}${pad(Math.floor(Math.abs(offset) / 60))}:${pad(Math.abs(offset) % 60)}`;
  return (
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ` +
    `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}.${pad(date.getMilliseconds(), 3)} ${zone}`
  );
}

/** 详情页子表格里的绝对时间：2026-11-13 10:02（本地时区，到分钟；秒、毫秒与时区在悬停的提示里不需要时用它）。 */
export function formatDateTime(iso: string): string {
  return formatInstant(iso).slice(0, 16);
}

/** 字节数：512 B、12.4 KB、3.1 MB。 */
export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${trimZero((bytes / 1024).toFixed(1))} KB`;
  return `${trimZero((bytes / 1024 / 1024).toFixed(1))} MB`;
}

/** 中间省略的 ID：保留前缀与末尾（ses_01JC8Z4V…X3M9N2），完整值在复制与提示中。不超过 `head + tail + 2` 个字符时原样返回。 */
export function middleEllipsis(id: string, head = 10, tail = 6): string {
  return id.length > head + tail + 2 ? `${id.slice(0, head)}…${id.slice(-tail)}` : id;
}

const monthDayTime = new Intl.DateTimeFormat('zh-CN', {
  month: 'numeric',
  day: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
  hourCycle: 'h23',
});

/**
 * 列表中的相对时间：刚刚、5 分钟前、3 小时前、2 天前；一周以上显示日期（同一年省略年份）。未来的时间写"后"。
 */
export function formatRelative(iso: string, now: number): string {
  const time = Date.parse(iso);
  const diff = now - time;
  const past = diff >= 0;
  const abs = Math.abs(diff);
  const suffix = past ? '前' : '后';
  if (abs < 45_000) return past ? '刚刚' : '即将';
  if (abs < 3_600_000) return `${Math.max(1, Math.round(abs / 60_000))} 分钟${suffix}`;
  if (abs < 86_400_000) return `${Math.round(abs / 3_600_000)} 小时${suffix}`;
  if (abs < 7 * 86_400_000) return `${Math.round(abs / 86_400_000)} 天${suffix}`;
  const date = new Date(time);
  if (date.getFullYear() === new Date(now).getFullYear()) {
    const parts = Object.fromEntries(monthDayTime.formatToParts(date).map((p) => [p.type, p.value]));
    return `${parts['month']} 月 ${parts['day']} 日 ${parts['hour']}:${parts['minute']}`;
  }
  return `${date.getFullYear()} 年 ${date.getMonth() + 1} 月 ${date.getDate()} 日`;
}

/**
 * 金额：保留两位小数并标明币种（¥1.24、US$0.38）；大于 0 但不足一分时写"不足 ¥0.01"，不显示成 0。
 */
export function formatMoney(amount: number, currency: string): string {
  const format = new Intl.NumberFormat('zh-CN', {
    style: 'currency',
    currency,
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });
  if (amount > 0 && amount < 0.005) return `不足 ${format.format(0.01)}`;
  return format.format(amount);
}
