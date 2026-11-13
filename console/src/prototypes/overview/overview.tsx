import { useEffect, useEffectEvent, useMemo, useState, type MouseEvent, type ReactNode } from 'react';

import { CopyableId } from '@/components/domain/copyable-id';
import { EmptyState } from '@/components/domain/empty-state';
import { ErrorState } from '@/components/domain/error-state';
import { PermissionGate } from '@/components/domain/permission-gate';
import { RelativeTime } from '@/components/domain/relative-time';
import { StatusGlyph } from '@/components/domain/status-badge';
import { InfoIcon } from '@/components/ui/glyphs';
import { Icon } from '@/components/ui/icon';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Skeleton } from '@/components/ui/skeleton';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import { ApiError, getSessionStats, listAgents } from '@/lib/api/sessions';
import type { Schemas } from '@/lib/api/types';
import { cn } from '@/lib/cn';
import { formatClock, formatInteger, formatMoney, formatTokens } from '@/lib/format';
import { useDelayedVisible } from '@/lib/use-delayed-visible';

import { buildBudgetStatus, buildDailyUsage, type BudgetStatus, type DailyUsage } from '../fixtures/usage';
import { useInterval, useResource } from '../shared/use-resource';
import { DetailPage } from '../templates/detail-page';

import { UsageChart } from './usage-chart';

type Window = Schemas['StatsWindow'];

const WINDOWS: Record<Window, string> = { '1h': '最近 1 小时', '24h': '最近 24 小时', '7d': '最近 7 天' };

/** 失败率达到它时标出（信号红加叉）。 */
const ALERT_RATE = 0.1;

export interface OverviewProps {
  /** 用量与预算（管理 API）的加载情形；原型用视图模型模拟。 */
  usage?: 'ready' | 'loading' | 'error' | 'empty';
  /** 当前用户能否查看用量（USAGE_READ）。 */
  canReadUsage?: boolean;
  /** 运行概况的轮询间隔：正式为 30 秒。 */
  pollMs?: number;
}

const stay = (e: MouseEvent) => e.preventDefault();

function Section({
  title,
  hint,
  actions,
  className,
  children,
}: {
  title: string;
  hint?: string;
  actions?: ReactNode;
  className?: string;
  children: ReactNode;
}) {
  return (
    <section aria-label={title} className={cn('flex min-w-0 flex-col gap-4', className)}>
      <div className="flex min-h-8 items-center gap-2">
        <h2 className="text-16 font-semibold">{title}</h2>
        {hint && (
          <Tooltip>
            <TooltipTrigger asChild>
              <button
                type="button"
                aria-label={`${title}的说明`}
                className="inline-flex rounded-control text-ink-2 hover:text-ink"
              >
                <Icon glyph={InfoIcon} />
              </button>
            </TooltipTrigger>
            <TooltipContent>{hint}</TooltipContent>
          </Tooltip>
        )}
        {actions && <div className="ml-auto flex items-center gap-2">{actions}</div>}
      </div>
      {children}
    </section>
  );
}

/** 运行状况：三个可点击的计数，排成一行，不做成卡片。 */
function Health({ stats, skeleton }: { stats: Schemas['SessionStats'] | null; skeleton: boolean }) {
  const items = [
    {
      kind: 'running' as const,
      label: '运行中的会话',
      value: stats?.running_sessions,
      href: '/sessions?status=running',
    },
    {
      kind: 'requires_action' as const,
      label: '等待回应的会话',
      value: stats?.requires_action_sessions,
      href: '/sessions?status=requires_action',
    },
    {
      kind: 'requires_action' as const,
      label: '待处理动作',
      value: stats?.pending_actions,
      href: '/pending',
    },
  ];
  return (
    <ul aria-label="运行状况" className="grid grid-cols-3 border-y border-rule">
      {items.map((item, i) => (
        <li key={item.label} className={cn(i > 0 && 'border-l border-rule')}>
          <a
            href={item.href}
            onClick={stay}
            className="flex h-24 flex-col justify-center gap-2 px-6 transition-colors duration-150 ease-out hover:bg-surface-hover"
          >
            <span className="flex items-center gap-2 text-13 text-ink-2">
              <StatusGlyph kind={item.kind} />
              {item.label}
            </span>
            {item.value === undefined ? (
              // 数据到之前占住同样的高度；超过 150ms 才显示骨架。
              <Skeleton className={cn('h-9 w-12', !skeleton && 'invisible')} />
            ) : (
              <span className="text-28 font-semibold text-ink">{formatInteger(item.value)}</span>
            )}
          </a>
        </li>
      ))}
    </ul>
  );
}

function rateText(rate: number | null): ReactNode {
  if (rate === null) return <span className="text-ink-2">无轮次</span>;
  const text = `${(rate * 100).toFixed(1)}%`;
  if (rate < ALERT_RATE) return text;
  return (
    <span className="inline-flex items-center gap-1 font-medium text-danger">
      <StatusGlyph kind="failure" />
      {text}
    </span>
  );
}

function AgentQuality({
  stats,
  names,
}: {
  stats: Schemas['SessionStats'];
  names: ReadonlyMap<string, string>;
}) {
  const rows = [...stats.agents].sort((a, b) => (b.failure_rate ?? -1) - (a.failure_rate ?? -1));
  if (rows.length === 0)
    return <EmptyState variant="clear" size="inline" title="这个时间窗内没有智能体运行过" />;
  return (
    <Table aria-label="按智能体的失败率" className="table-fixed">
      <colgroup>
        <col />
        <col style={{ width: 96 }} />
        <col style={{ width: 96 }} />
        <col style={{ width: 112 }} />
      </colgroup>
      <TableHeader>
        <TableRow className="hover:bg-transparent">
          <TableHead>智能体</TableHead>
          <TableHead className="text-right">轮次</TableHead>
          <TableHead className="text-right">失败</TableHead>
          <TableHead className="text-right">失败率</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {rows.map((a) => (
          <TableRow key={a.agent_id}>
            <TableCell className="truncate">{names.get(a.agent_id) ?? a.agent_id}</TableCell>
            <TableCell className="text-right tabular-nums">{formatInteger(a.turns)}</TableCell>
            <TableCell className="text-right tabular-nums">{formatInteger(a.failed_turns)}</TableCell>
            <TableCell className="text-right tabular-nums">{rateText(a.failure_rate)}</TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  );
}

function RecentErrors({
  stats,
  names,
}: {
  stats: Schemas['SessionStats'];
  names: ReadonlyMap<string, string>;
}) {
  if (stats.recent_errors.length === 0)
    return <EmptyState variant="clear" size="inline" title="这个时间窗内没有失败的轮次" />;
  return (
    <Table aria-label="最近失败的轮次" className="table-fixed">
      <colgroup>
        <col style={{ width: 104 }} />
        <col style={{ width: 120 }} />
        <col style={{ width: 224 }} />
        <col />
        <col style={{ width: 208 }} />
      </colgroup>
      <TableHeader>
        <TableRow className="hover:bg-transparent">
          <TableHead>时间</TableHead>
          <TableHead>智能体</TableHead>
          <TableHead>错误码</TableHead>
          <TableHead>原因</TableHead>
          <TableHead>会话</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {stats.recent_errors.map((e) => (
          <TableRow key={e.turn_id}>
            <TableCell>
              <RelativeTime value={e.occurred_at} className="text-ink-2" />
            </TableCell>
            <TableCell className="truncate">
              {e.agent_id ? (names.get(e.agent_id) ?? e.agent_id) : '未记录'}
            </TableCell>
            <TableCell className="truncate font-mono text-12">{e.code ?? '未知'}</TableCell>
            <TableCell className="truncate" title={e.message ?? undefined}>
              {e.message ?? <span className="text-ink-2">找不到这一轮的错误事件</span>}
            </TableCell>
            <TableCell>
              <CopyableId id={e.session_id} label="会话 ID" />
            </TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  );
}

function Budget({ budget }: { budget: BudgetStatus }) {
  const hard = budget.hardLimit;
  const ratio = hard ? Math.min(1, budget.usedTokens / hard) : 0;
  const soft = hard && budget.softLimit ? budget.softLimit / hard : null;
  const tone =
    budget.state === 'hard_exceeded'
      ? 'bg-danger'
      : budget.state === 'soft_exceeded'
        ? 'bg-brass'
        : 'bg-accent';
  const percent = Math.round(ratio * 100);
  return (
    <div className="flex flex-col gap-4">
      <div className="flex items-baseline gap-2">
        <span className="text-28 font-semibold">{formatTokens(budget.usedTokens)}</span>
        <span className="text-13 text-ink-2">
          {hard ? `/ ${formatTokens(hard)} token，已用 ${percent}%` : 'token，没有设置上限'}
        </span>
      </div>
      {hard && (
        <div
          role="meter"
          aria-label="本月预算已用"
          aria-valuemin={0}
          aria-valuemax={100}
          aria-valuenow={percent}
          aria-valuetext={`已用 ${percent}%`}
          className="relative h-2 rounded-control bg-surface-inset"
        >
          <div className={cn('h-full rounded-control', tone)} style={{ width: `${ratio * 100}%` }} />
          {soft !== null && (
            <span
              aria-hidden
              className="absolute -top-1 -bottom-1 w-px bg-ink-2"
              style={{ left: `${soft * 100}%` }}
            />
          )}
        </div>
      )}
      <dl className="grid grid-cols-[auto_minmax(0,1fr)] gap-x-6 gap-y-2 text-13">
        <dt className="text-ink-2">提醒阈值</dt>
        <dd className="text-right tabular-nums">
          {budget.softLimit ? `${formatTokens(budget.softLimit)} token` : '未设置'}
        </dd>
        <dt className="text-ink-2">上限</dt>
        <dd className="text-right tabular-nums">{hard ? `${formatTokens(hard)} token` : '未设置'}</dd>
        <dt className="text-ink-2">设置来源</dt>
        <dd className="text-right">
          {{ project: '本项目的设置', default: '租户默认值', unset: '没有设置' }[budget.source]}
        </dd>
      </dl>
    </div>
  );
}

/** 视图模型数据的模拟加载（管理 API 在 T21 接入）：给定延迟后得到数据或错误。 */
function useFixture<T>(make: () => T, state: OverviewProps['usage']): { data: T | null; error: unknown } {
  const [data, setData] = useState<T | null>(null);
  const build = useEffectEvent(make);
  useEffect(() => {
    if (state === 'loading' || state === 'error') return undefined;
    const timer = window.setTimeout(() => setData(build()), 320);
    return () => window.clearTimeout(timer);
  }, [state]);
  const error =
    state === 'error'
      ? new ApiError(503, {
          type: 'urn:meridian:error:internal.unavailable',
          title: 'Service Unavailable',
          status: 503,
          detail: '控制面正在重启，暂时无法读取用量。',
          instance:
            '/admin/v1/tenants/tnt_01JB5H1N7R3V9X5A1C7E3G9JM/projects/prj_01JB6M2Q8T4W0Y6B2D8F4H0KQ/usage',
          code: 'internal.unavailable',
          request_id: 'req_01JCQD7K0M3P6R9T2W5Y8B1D4F',
        })
      : null;
  return { data, error };
}

/**
 * 概览（详情页模板，不分标签页）：运行状况、本月用量趋势与预算、按智能体的失败率与最近的失败轮次。每块区域各自加载、
 * 各自报错，互不阻塞；运行概况每 30 秒轮询，数字就地更新，不做滚动计数。
 */
export function Overview({ usage = 'ready', canReadUsage = true, pollMs = 30_000 }: OverviewProps) {
  const [statsWindow, setStatsWindow] = useState<Window>('24h');
  const stats = useResource((signal) => getSessionStats({ window: statsWindow }, { signal }), statsWindow);
  const agents = useResource((signal) => listAgents({ limit: 100 }, { signal }), 'agents');
  const names = useMemo(() => new Map((agents.data?.data ?? []).map((a) => [a.id, a.name])), [agents.data]);
  const [latest, setLatest] = useState<Schemas['SessionStats'] | null>(null);
  const shown = latest?.window === statsWindow ? latest : stats.data;
  const statsSkeleton = useDelayedVisible(stats.loading && shown === null);

  useInterval(() => {
    void getSessionStats({ window: statsWindow })
      .then(setLatest)
      .catch(() => undefined);
  }, pollMs);

  const [now] = useState(() => Date.now());
  const daily = useFixture<DailyUsage[]>(
    () =>
      usage === 'empty'
        ? buildDailyUsage(now).map((d) => ({ ...d, tokens: 0, cost: 0 }))
        : buildDailyUsage(now),
    usage,
  );
  const budget = useFixture<BudgetStatus>(
    () =>
      usage === 'empty' ? { ...buildBudgetStatus(now), usedTokens: 0, state: 'ok' } : buildBudgetStatus(now),
    usage,
  );
  const usageSkeleton = useDelayedVisible(daily.data === null && daily.error === null);
  const total = daily.data?.reduce((s, d) => s + d.tokens, 0) ?? 0;
  const cost = daily.data?.reduce((s, d) => s + d.cost, 0) ?? 0;

  const usageBlock = (body: ReactNode) =>
    canReadUsage ? (
      body
    ) : (
      <PermissionGate
        mode="explain"
        allowed={false}
        title="没有权限查看用量"
        reason="查看用量需要本项目的管理员或所有者角色，请联系项目管理员。"
      />
    );

  return (
    <DetailPage title="概览">
      <div className="flex flex-col gap-12">
        {stats.error && !shown ? (
          <ErrorState title="无法加载运行状况" error={stats.error} onRetry={stats.reload} size="inline" />
        ) : (
          <Health stats={shown} skeleton={statsSkeleton} />
        )}

        {canReadUsage && daily.error ? (
          // 用量与预算来自同一个接口：失败时只报一次错，占这一行。
          <Section title="用量与预算">
            <ErrorState
              title="无法加载用量与预算"
              error={daily.error}
              onRetry={() => undefined}
              size="inline"
            />
          </Section>
        ) : (
          <div className="grid grid-cols-1 gap-12 xl:grid-cols-[minmax(0,1fr)_320px]">
            <Section
              title="最近 14 天的用量"
              actions={
                daily.data &&
                canReadUsage &&
                total > 0 && (
                  <span className="text-13 text-ink-2 tabular-nums">
                    合计 <span className="text-ink">{formatTokens(total)}</span> token，成本{' '}
                    <span className="text-ink">{formatMoney(cost, 'CNY')}</span>
                  </span>
                )
              }
            >
              {usageBlock(
                daily.error ? (
                  <ErrorState
                    title="无法加载用量"
                    error={daily.error}
                    onRetry={() => undefined}
                    size="inline"
                  />
                ) : daily.data === null ? (
                  usageSkeleton && <Skeleton className="h-64 w-full" />
                ) : total === 0 ? (
                  <EmptyState
                    variant="first-use"
                    size="inline"
                    title="还没有用量，会话开始调用模型后按天显示在这里"
                  />
                ) : (
                  <UsageChart data={daily.data} className="h-64" />
                ),
              )}
            </Section>
            <Section title="本月预算" hint="达到上限后，本项目新的模型调用会被拒绝；提醒阈值只发告警">
              {usageBlock(
                budget.error ? (
                  <ErrorState
                    title="无法加载预算"
                    error={budget.error}
                    onRetry={() => undefined}
                    size="inline"
                  />
                ) : budget.data === null ? (
                  usageSkeleton && (
                    <div aria-hidden className="flex flex-col gap-4">
                      <Skeleton className="h-9 w-40" />
                      <Skeleton className="h-2 w-full" />
                      <Skeleton className="h-16 w-full" />
                    </div>
                  )
                ) : (
                  <Budget budget={budget.data} />
                ),
              )}
            </Section>
          </div>
        )}

        <Section
          title="运行质量"
          hint={
            shown?.stats_updated_at
              ? `失败率按小时汇总，统计到 ${formatClock(shown.stats_updated_at).slice(0, 5)}；被取消的轮次不计入`
              : '失败率按小时汇总；被取消的轮次不计入'
          }
          actions={
            <Select value={statsWindow} onValueChange={(v) => setStatsWindow(v as Window)}>
              <SelectTrigger size="sm" className="w-36" aria-label="统计时间窗">
                <SelectValue />
              </SelectTrigger>
              <SelectContent align="end">
                {(Object.keys(WINDOWS) as Window[]).map((w) => (
                  <SelectItem key={w} value={w}>
                    {WINDOWS[w]}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          }
        >
          {stats.error && !shown ? (
            <ErrorState title="无法加载运行质量" error={stats.error} onRetry={stats.reload} size="inline" />
          ) : !shown ? (
            statsSkeleton && <Skeleton className="h-48 w-full" />
          ) : (
            <div className="grid grid-cols-1 gap-8 2xl:grid-cols-[minmax(0,2fr)_minmax(0,3fr)] 2xl:gap-12">
              <div className="flex min-w-0 flex-col gap-2">
                <h3 className="text-13 font-semibold">按智能体</h3>
                <AgentQuality stats={shown} names={names} />
              </div>
              <div className="flex min-w-0 flex-col gap-2">
                <h3 className="text-13 font-semibold">最近失败的轮次</h3>
                <RecentErrors stats={shown} names={names} />
              </div>
            </div>
          )}
        </Section>
      </div>
    </DetailPage>
  );
}
