/**
 * 概览的用量视图模型。正式数据来自管理 API：按天的用量 `GET P:/usage?group_by=day`（金额是十进制字符串，按天、种类分行，
 * T25a 合并为每天一行）与本周期预算 `GET P:/budget-status`。这里不是 API 类型。
 */

export interface DailyUsage {
  /** 本地日期 YYYY-MM-DD。 */
  day: string;
  /** 输入加输出 token。 */
  tokens: number;
  /** 成本（元）。 */
  cost: number;
}

export interface BudgetStatus {
  /** 预算周期（自然月）。 */
  period: string;
  usedTokens: number;
  /** 提醒阈值与上限（token）；null 表示没有设置。 */
  softLimit: number | null;
  hardLimit: number | null;
  /** 阈值的来源：项目自己的设置、租户默认值，或都没有设置。 */
  source: 'project' | 'default' | 'unset';
  state: 'ok' | 'soft_exceeded' | 'hard_exceeded';
}

function pad(n: number): string {
  return String(n).padStart(2, '0');
}

function localDay(date: Date): string {
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

/** 最近 `days` 天（含今天，今天只到现在）的用量；工作日多、周末少，每 1M token 约 1.15 元。 */
export function buildDailyUsage(now: number, days = 14): DailyUsage[] {
  const rows: DailyUsage[] = [];
  const today = new Date(now);
  for (let i = days - 1; i >= 0; i -= 1) {
    const date = new Date(today.getFullYear(), today.getMonth(), today.getDate() - i);
    const weekend = date.getDay() === 0 || date.getDay() === 6;
    // 固定的起伏，不用随机数：同一天总是同一个值。
    const wave = Math.sin(date.getDate() * 1.7) * 0.18 + 1;
    let tokens = (weekend ? 11_000_000 : 36_000_000) * wave;
    if (i === 0) tokens *= Math.min(1, (today.getHours() * 60 + today.getMinutes()) / (18 * 60));
    tokens = Math.round(tokens / 1000) * 1000;
    rows.push({ day: localDay(date), tokens, cost: Math.round((tokens / 1_000_000) * 1.15 * 100) / 100 });
  }
  return rows;
}

/** 本月的预算状态：用量从每天的用量累加。 */
export function buildBudgetStatus(now: number): BudgetStatus {
  const today = new Date(now);
  const used = buildDailyUsage(now, today.getDate()).reduce((sum, d) => sum + d.tokens, 0);
  const hardLimit = 1_200_000_000;
  const softLimit = 960_000_000;
  return {
    period: `${today.getFullYear()}-${pad(today.getMonth() + 1)}`,
    usedTokens: used,
    softLimit,
    hardLimit,
    source: 'project',
    state: used >= hardLimit ? 'hard_exceeded' : used >= softLimit ? 'soft_exceeded' : 'ok',
  };
}
