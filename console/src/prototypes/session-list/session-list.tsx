import { useMemo, useState } from 'react';

import { CopyableId } from '@/components/domain/copyable-id';
import { DataTable, dataTableColumns, type CursorPagination } from '@/components/domain/data-table';
import { EmptyState } from '@/components/domain/empty-state';
import { ErrorState } from '@/components/domain/error-state';
import {
  FilterBar,
  emptyValues,
  hasActiveFilters,
  type FilterDef,
  type FilterValues,
} from '@/components/domain/filter-bar';
import { PermissionGate } from '@/components/domain/permission-gate';
import { RelativeTime } from '@/components/domain/relative-time';
import { SESSION_STATUS_LABEL, StatusBadge } from '@/components/domain/status-badge';
import { Button } from '@/components/ui/button';
import { FlaskIcon } from '@/components/ui/glyphs';
import { Icon } from '@/components/ui/icon';
import { listAgents, listSessions } from '@/lib/api/sessions';
import type { Session, SessionStatus } from '@/lib/api/types';

import { useInterval, useResource } from '../shared/use-resource';
import { ListPage } from '../templates/list-page';

const PAGE_SIZE = 50;

const CREATED_WITHIN: Record<string, { label: string; ms: number }> = {
  '1h': { label: '最近 1 小时', ms: 3_600_000 },
  '24h': { label: '最近 24 小时', ms: 86_400_000 },
  '7d': { label: '最近 7 天', ms: 7 * 86_400_000 },
  '30d': { label: '最近 30 天', ms: 30 * 86_400_000 },
};

const STATUSES: SessionStatus[] = ['running', 'requires_action', 'idle', 'rescheduled', 'terminated'];

export interface SessionListProps {
  /** 当前用户能否查看会话（由 `GET /admin/v1/me/access` 的作用域算出）。 */
  canRead?: boolean;
  /** 初始筛选（正式页面来自 URL 的搜索参数）。 */
  initialFilters?: FilterValues;
  /** 轮询间隔：正式为 15 秒，故事里缩短以演示"有 N 条更新"。 */
  pollMs?: number;
}

const col = dataTableColumns<Session>();

/** 筛选取值到 `GET /v1/sessions` 的查询参数：搜索词是会话 ID 时直接打开（不进查询），其余按终端用户筛选。 */
function toQuery(filters: FilterValues) {
  const created = filters.filters['created']?.[0];
  const q = filters.q;
  return {
    agent_id: filters.filters['agent_id']?.[0],
    status: filters.filters['status']?.[0] as SessionStatus | undefined,
    end_user_id: q && !q.startsWith('ses_') ? q : undefined,
    created_after: created
      ? new Date(Date.now() - (CREATED_WITHIN[created]?.ms ?? 0)).toISOString()
      : undefined,
  };
}

/** 一页会话和它对应的查询；新查询的结果到来之前保留旧的，加载更多时在后面追加。 */
interface Loaded {
  key: string;
  rows: Session[];
  cursor: string | null;
}

/**
 * 会话列表（列表页模板）：按智能体、状态、创建时间筛选，搜索框输入会话 ID 时直接打开、否则按终端用户筛选；
 * 游标分页"加载更多"；每 15 秒轮询，有变化时在表头上方提示"有 N 条更新"，点击后刷新，不打断正在看的列表。
 */
export function SessionList({ canRead = true, initialFilters, pollMs = 15_000 }: SessionListProps) {
  const agents = useResource((signal) => listAgents({ limit: 100 }, { signal }), 'agents');
  const agentNames = useMemo(
    () => new Map((agents.data?.data ?? []).map((a) => [a.id, a.name])),
    [agents.data],
  );
  const defs = useMemo<FilterDef[]>(
    () => [
      {
        key: 'agent_id',
        label: '智能体',
        options: (agents.data?.data ?? []).map((a) => ({ value: a.id, label: a.name })),
      },
      {
        key: 'status',
        label: '状态',
        options: STATUSES.map((s) => ({ value: s, label: SESSION_STATUS_LABEL[s] })),
      },
      {
        key: 'created',
        label: '创建时间',
        options: Object.entries(CREATED_WITHIN).map(([value, { label }]) => ({ value, label })),
      },
    ],
    [agents.data],
  );
  const [filters, setFilters] = useState<FilterValues>(() => initialFilters ?? emptyValues(defs));
  // 查询参数在筛选变化时算出（创建时间范围按那一刻换算成 created_after）。
  const [query, setQuery] = useState(() => toQuery(filters));
  const [active, setActive] = useState<string | null>(null);
  const key = JSON.stringify(filters);

  const first = useResource((signal) => listSessions({ ...query, limit: PAGE_SIZE }, { signal }), key);
  const [more, setMore] = useState<Loaded | null>(null);
  const [loadingMore, setLoadingMore] = useState(false);
  const [fresh, setFresh] = useState<{ key: string; rows: Session[]; count: number } | null>(null);

  const loaded: Loaded | null = first.data
    ? more?.key === key
      ? more
      : { key, rows: first.data.data, cursor: first.data.next_cursor }
    : null;
  const rows = loaded?.rows ?? [];

  const loadMore = () => {
    if (!loaded?.cursor) return;
    setLoadingMore(true);
    void listSessions({ ...query, limit: PAGE_SIZE, after: loaded.cursor })
      .then((page) => setMore({ key, rows: [...loaded.rows, ...page.data], cursor: page.next_cursor }))
      .finally(() => setLoadingMore(false));
  };

  // 轮询第一页：新出现的会话与状态、更新时间变了的会话计为更新。
  useInterval(
    () => {
      if (!loaded) return;
      listSessions({ ...query, limit: PAGE_SIZE })
        .then((page) => {
          const known = new Map(loaded.rows.map((s) => [s.id, s]));
          const count = page.data.filter((s) => {
            const old = known.get(s.id);
            return !old || old.status !== s.status || old.updated_at !== s.updated_at;
          }).length;
          if (count > 0) setFresh({ key, rows: page.data, count });
        })
        .catch(() => undefined);
    },
    canRead ? pollMs : null,
  );

  const pagination: CursorPagination | undefined = loaded
    ? { hasMore: loaded.cursor !== null, loadingMore, onLoadMore: loadMore }
    : undefined;

  const columns = useMemo(
    () =>
      col.columns([
        col.accessor('id', {
          header: '会话',
          enableHiding: false,
          enableSorting: false,
          meta: { width: 216 },
          cell: (c) => <CopyableId id={c.getValue()} label="会话 ID" />,
        }),
        col.accessor((s) => (s.agent_id ? (agentNames.get(s.agent_id) ?? s.agent_id) : '未记录'), {
          id: 'agent',
          header: '智能体',
          enableSorting: false,
          meta: { label: '智能体' },
          cell: (c) => <span className="truncate">{c.getValue()}</span>,
        }),
        col.accessor('status', {
          header: '状态',
          enableSorting: false,
          meta: { width: 128 },
          cell: (c) => <StatusBadge of="session" status={c.getValue()} />,
        }),
        col.accessor('end_user_id', {
          header: '终端用户',
          enableSorting: false,
          meta: { width: 200 },
          cell: (c) => {
            const v = c.getValue();
            return v ? <span className="truncate">{v}</span> : <span className="text-ink-2">无</span>;
          },
        }),
        col.accessor('created_at', {
          header: '创建',
          enableSorting: false,
          meta: { width: 120 },
          cell: (c) => <RelativeTime value={c.getValue()} className="text-ink-2" />,
        }),
        col.accessor('updated_at', {
          header: '更新',
          enableSorting: false,
          meta: { width: 120 },
          cell: (c) => <RelativeTime value={c.getValue()} className="text-ink-2" />,
        }),
      ]),
    [agentNames],
  );

  const onFilters = (next: FilterValues) => {
    if (next.q.startsWith('ses_')) {
      // 正式页面跳到会话详情（T24）；原型中标出这一行。
      setActive(next.q);
      return;
    }
    setFilters(next);
    setQuery(toQuery(next));
    setFresh(null);
  };

  if (!canRead) {
    return (
      <ListPage title="会话">
        <PermissionGate
          mode="explain"
          allowed={false}
          title="没有权限查看会话"
          reason="查看会话需要本项目的查看者及以上角色，请联系项目管理员。"
        />
      </ListPage>
    );
  }

  const filtered = hasActiveFilters(filters);
  const updates =
    fresh?.key === key && fresh.count > 0
      ? {
          count: fresh.count,
          onApply: () => {
            setMore({ key, rows: fresh.rows, cursor: null });
            setFresh(null);
          },
        }
      : undefined;

  return (
    <ListPage title="会话">
      <DataTable
        label="会话列表"
        columns={columns}
        data={rows}
        getRowId={(s) => s.id}
        loading={first.loading}
        error={
          first.error ? (
            <ErrorState title="无法加载会话列表" error={first.error} onRetry={first.reload} />
          ) : undefined
        }
        empty={
          filtered ? (
            <EmptyState
              variant="filtered"
              title="没有匹配的会话"
              onClearFilters={() => onFilters(emptyValues(defs))}
            />
          ) : (
            <EmptyState
              variant="first-use"
              title="业务系统调用会话接口、或在 Playground 中对话后，会话出现在这里"
              action={
                <Button variant="primary" icon={<Icon glyph={FlaskIcon} />}>
                  在 Playground 中对话
                </Button>
              }
            />
          )
        }
        pagination={pagination}
        onRowClick={(s) => setActive(s.id)}
        activeRowId={active}
        updates={updates}
        className="min-h-0 flex-1"
        toolbar={
          <FilterBar
            filters={defs}
            value={filters}
            onChange={onFilters}
            search={{ label: '搜索会话', placeholder: '会话 ID 或终端用户' }}
          />
        }
      />
    </ListPage>
  );
}
