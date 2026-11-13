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

/** 字节数：512 B、12.4 KB、3.1 MB。 */
export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${trimZero((bytes / 1024).toFixed(1))} KB`;
  return `${trimZero((bytes / 1024 / 1024).toFixed(1))} MB`;
}
