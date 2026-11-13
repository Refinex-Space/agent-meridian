import { useMemo, useState } from 'react';

import { DataTable, dataTableColumns } from '@/components/domain/data-table';
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
import { StatusBadge } from '@/components/domain/status-badge';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { listAgents, listPendingActions, sendEvents } from '@/lib/api/sessions';
import type { PendingAction, Schemas } from '@/lib/api/types';
import { useNow } from '@/lib/use-now';

import { useInterval, useResource } from '../shared/use-resource';
import { ListPage } from '../templates/list-page';

import { ActionSheet, type ActionResponse } from './action-sheet';
import { ACTION_TYPE_LABEL, inputSummary, toolLabel } from './describe';

type Tab = 'pending' | 'history';
type Conclusion = Schemas['PendingActionConclusion'];
type Busy = { id: string; kind: 'approve' | 'deny' | 'retry' | 'skip' };

export interface PendingActionsProps {
  /** 当前用户能否回应（`actions:respond` 或 `sessions:write`）；不能时说明缺少的角色。 */
  canRespond?: boolean;
  initialTab?: Tab;
  initialFilters?: FilterValues;
  /** 打开时就选中的动作（故事里直接展示详情抽屉）。 */
  initialSelected?: string;
  /** 处理人的显示名（正式页面从成员与 API Key 接口取）。 */
  deciders: Readonly<Record<string, string>>;
  /** 轮询间隔：正式为 15 秒。 */
  pollMs?: number;
}

const DENIED = '回应待处理动作需要本项目的审批人、开发者或所有者角色，请联系项目管理员。';
const TYPES = Object.keys(ACTION_TYPE_LABEL) as Schemas['PendingActionType'][];
const col = dataTableColumns<PendingAction>();

function conclusionOf(response: ActionResponse): Conclusion {
  return {
    origin: 'caller',
    approved: response.approved ?? null,
    deny_reason: null,
    remember_scope: response.rememberScope ?? null,
    remember_rule: response.rememberRule ?? null,
    decision: response.decision ?? null,
    error: null,
  };
}

/**
 * 待处理（列表页模板加标签页）：待回应的动作按类型、智能体筛选，行内直接批准或拒绝（重试或跳过），点行在抽屉里看完整入参、
 * 选择记住此决定；历史列出结论、来源与处理人。每 15 秒轮询，新动作以"有 N 条更新"提示。
 */
export function PendingActions({
  canRespond = true,
  initialTab = 'pending',
  initialFilters,
  initialSelected,
  deciders,
  pollMs = 15_000,
}: PendingActionsProps) {
  const now = useNow();
  const [tab, setTab] = useState<Tab>(initialTab);
  const agents = useResource((signal) => listAgents({ limit: 100 }, { signal }), 'agents');
  const agentNames = useMemo(
    () => new Map((agents.data?.data ?? []).map((a) => [a.id, a.name])),
    [agents.data],
  );
  const defs = useMemo<FilterDef[]>(
    () => [
      { key: 'type', label: '类型', options: TYPES.map((t) => ({ value: t, label: ACTION_TYPE_LABEL[t] })) },
      {
        key: 'agent_id',
        label: '智能体',
        options: (agents.data?.data ?? []).map((a) => ({ value: a.id, label: a.name })),
      },
    ],
    [agents.data],
  );
  const [filters, setFilters] = useState<FilterValues>(() => initialFilters ?? emptyValues(defs));
  const query = {
    type: filters.filters['type']?.[0] as Schemas['PendingActionType'] | undefined,
    agent_id: filters.filters['agent_id']?.[0],
    session_id: filters.q || undefined,
  };
  const key = JSON.stringify([tab, filters]);
  const list = useResource(async (signal) => {
    // 历史包括已回应与已超时两种状态。
    if (tab === 'pending')
      return (await listPendingActions({ ...query, status: 'pending', limit: 50 }, { signal })).data;
    const [resolved, expired] = await Promise.all([
      listPendingActions({ ...query, status: 'resolved', limit: 50 }, { signal }),
      listPendingActions({ ...query, status: 'expired', limit: 50 }, { signal }),
    ]);
    return [...resolved.data, ...expired.data].sort(
      (a, b) => Date.parse(b.resolved_at ?? b.created_at) - Date.parse(a.resolved_at ?? a.created_at),
    );
  }, key);
  const [selected, setSelected] = useState<string | null>(initialSelected ?? null);
  const [busy, setBusy] = useState<Busy | null>(null);
  const [responded, setResponded] = useState<ReadonlyMap<string, Conclusion>>(new Map());
  const [respondError, setRespondError] = useState<unknown>(null);
  const [arrived, setArrived] = useState(0);

  const rows = list.data ?? [];
  const selectedAction = rows.find((a) => a.id === selected) ?? null;

  const respond = (action: PendingAction, response: ActionResponse, kind: Busy['kind']) => {
    setBusy({ id: action.id, kind });
    setRespondError(null);
    const event =
      response.decision !== undefined
        ? { type: 'user.recovery_confirmation' as const, action_id: action.id, decision: response.decision }
        : {
            type: 'user.tool_confirmation' as const,
            action_id: action.id,
            approved: response.approved ?? false,
            ...(response.rememberScope && response.rememberScope !== 'none'
              ? {
                  remember_scope: response.rememberScope,
                  ...(response.rememberRule ? { remember_rule: response.rememberRule } : {}),
                }
              : {}),
          };
    sendEvents(action.session_id, { events: [event] })
      .then(() => setResponded((prev) => new Map(prev).set(action.id, conclusionOf(response))))
      .catch((e: unknown) => setRespondError(e))
      .finally(() => setBusy(null));
  };

  // 轮询待回应的动作：出现新的动作时提示，不打断正在处理的列表。
  useInterval(() => {
    if (tab !== 'pending' || !list.data) return;
    const known = new Set(list.data.map((a) => a.id));
    listPendingActions({ ...query, status: 'pending', limit: 50 })
      .then((page) => setArrived(page.data.filter((a) => !known.has(a.id)).length))
      .catch(() => undefined);
  }, pollMs);

  const columns = useMemo(() => {
    const shared = [
      col.accessor('tool_name', {
        header: '工具',
        enableSorting: false,
        enableHiding: false,
        meta: { width: 216 },
        // 工具确认是绝大多数，只给其他类型标出类型。
        cell: (c) => (
          <span className="flex min-w-0 items-center gap-2">
            <span className="truncate font-mono text-12 text-ink">{toolLabel(c.getValue())}</span>
            {c.row.original.type !== 'tool_confirmation' && (
              <Badge>{ACTION_TYPE_LABEL[c.row.original.type]}</Badge>
            )}
          </span>
        ),
      }),
      col.accessor((a) => inputSummary(a), {
        id: 'input',
        header: '内容',
        enableSorting: false,
        enableHiding: false,
        cell: (c) => <span className="block truncate font-mono text-12 text-ink">{c.getValue()}</span>,
      }),
      col.accessor((a) => (a.agent_id ? (agentNames.get(a.agent_id) ?? a.agent_id) : '未记录'), {
        id: 'agent',
        header: '智能体',
        enableSorting: false,
        meta: { width: 128, label: '智能体' },
        cell: (c) => <span className="truncate">{c.getValue()}</span>,
      }),
    ];
    if (tab === 'history') {
      return col.columns([
        ...shared,
        col.accessor('status', {
          header: '结论',
          enableSorting: false,
          meta: { width: 152 },
          cell: (c) => (
            <StatusBadge of="action" status={c.getValue()} conclusion={c.row.original.conclusion} />
          ),
        }),
        col.accessor((a) => (a.decided_by ? (deciders[a.decided_by] ?? a.decided_by) : '平台'), {
          id: 'decider',
          header: '处理人',
          enableSorting: false,
          meta: { width: 128, label: '处理人' },
          cell: (c) => <span className="truncate">{c.getValue()}</span>,
        }),
        col.accessor((a) => a.resolved_at ?? a.created_at, {
          id: 'resolved_at',
          header: '时间',
          enableSorting: false,
          meta: { width: 104 },
          cell: (c) => <RelativeTime value={c.getValue()} className="text-ink-2" />,
        }),
      ]);
    }
    return col.columns([
      ...shared,
      col.accessor('expires_at', {
        header: '截止',
        enableSorting: false,
        meta: { width: 96 },
        cell: (c) => <RelativeTime value={c.getValue()} className="text-ink-2" />,
      }),
      col.display({
        id: 'respond',
        header: () => <span className="sr-only">回应</span>,
        enableHiding: false,
        meta: { width: 152, align: 'right', label: '回应' },
        cell: (c) => {
          const action = c.row.original;
          const done = responded.get(action.id);
          if (done) return <StatusBadge of="action" status="resolved" conclusion={done} />;
          if (action.type === 'custom_tool_result')
            return <span className="text-12 text-ink-2">由业务系统回传</span>;
          const recovery = action.type === 'recovery_confirmation';
          const mine = busy?.id === action.id ? busy.kind : null;
          const [yes, no] = recovery ? (['retry', 'skip'] as const) : (['approve', 'deny'] as const);
          return (
            // 行内按钮不触发行点击（打开抽屉）。
            // eslint-disable-next-line jsx-a11y/click-events-have-key-events, jsx-a11y/no-static-element-interactions
            <span className="inline-flex gap-2" onClick={(e) => e.stopPropagation()}>
              <PermissionGate allowed={canRespond} mode="disable" reason={DENIED}>
                <Button
                  size="sm"
                  pending={mine === no}
                  disabled={mine === yes}
                  onClick={() => respond(action, recovery ? { decision: 'skip' } : { approved: false }, no)}
                >
                  {recovery ? '跳过' : '拒绝'}
                </Button>
              </PermissionGate>
              <PermissionGate allowed={canRespond} mode="disable" reason={DENIED}>
                <Button
                  size="sm"
                  variant="primary"
                  pending={mine === yes}
                  disabled={mine === no}
                  onClick={() => respond(action, recovery ? { decision: 'retry' } : { approved: true }, yes)}
                >
                  {recovery ? '重试' : '批准'}
                </Button>
              </PermissionGate>
            </span>
          );
        },
      }),
    ]);
  }, [tab, agentNames, deciders, responded, busy, canRespond]);

  const filtered = hasActiveFilters(filters);

  // 表格放进当前标签页的面板：标签与面板经 aria-controls 关联（只挂载当前的一个）。
  const body = (
    <>
      {respondError !== null && (
        <ErrorState size="inline" title="回应没有发送成功" error={respondError} className="mt-3" />
      )}
      <DataTable
        label={tab === 'pending' ? '待回应的动作' : '已处理的动作'}
        columns={columns}
        data={rows}
        getRowId={(a) => a.id}
        loading={list.loading}
        error={
          list.error ? (
            <ErrorState title="无法加载待处理动作" error={list.error} onRetry={list.reload} />
          ) : undefined
        }
        empty={
          filtered ? (
            <EmptyState
              variant="filtered"
              title="没有匹配的动作"
              onClearFilters={() => setFilters(emptyValues(defs))}
            />
          ) : tab === 'pending' ? (
            <EmptyState variant="clear" title="没有等待回应的动作" />
          ) : (
            <EmptyState variant="clear" title="还没有处理过的动作" />
          )
        }
        onRowClick={(a) => setSelected(a.id)}
        activeRowId={selected}
        updates={
          arrived > 0
            ? {
                count: arrived,
                onApply: () => {
                  setArrived(0);
                  list.reload();
                },
              }
            : undefined
        }
        className="min-h-0 flex-1"
        toolbar={
          <FilterBar
            filters={defs}
            value={filters}
            onChange={setFilters}
            search={{ label: '按会话筛选', placeholder: '会话 ID' }}
          />
        }
      />
    </>
  );

  return (
    <Tabs value={tab} onValueChange={(v) => setTab(v as Tab)} className="h-full">
      <ListPage
        title="待处理"
        tabs={
          <TabsList>
            <TabsTrigger value="pending">待回应</TabsTrigger>
            <TabsTrigger value="history">历史</TabsTrigger>
          </TabsList>
        }
      >
        <TabsContent value="pending" className="flex min-h-0 flex-1 flex-col">
          {tab === 'pending' && body}
        </TabsContent>
        <TabsContent value="history" className="flex min-h-0 flex-1 flex-col">
          {tab === 'history' && body}
        </TabsContent>
      </ListPage>
      <ActionSheet
        action={selectedAction}
        agentName={selectedAction?.agent_id ? (agentNames.get(selectedAction.agent_id) ?? '') : '未记录'}
        deciderName={
          selectedAction?.decided_by
            ? (deciders[selectedAction.decided_by] ?? selectedAction.decided_by)
            : responded.has(selectedAction?.id ?? '')
              ? '我'
              : null
        }
        now={now}
        pending={busy && selectedAction && busy.id === selectedAction.id ? busy.kind : null}
        responded={selectedAction ? (responded.get(selectedAction.id) ?? null) : null}
        deniedReason={canRespond ? undefined : DENIED}
        onRespond={(response) => {
          if (!selectedAction) return;
          const kind: Busy['kind'] = response.decision ?? (response.approved ? 'approve' : 'deny');
          respond(selectedAction, response, kind);
        }}
        onOpenChange={(open) => !open && setSelected(null)}
      />
    </Tabs>
  );
}
