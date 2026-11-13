import { useQueries, useQuery } from '@tanstack/react-query';
import { useNavigate } from '@tanstack/react-router';
import { useMemo, useState } from 'react';

import { DangerConfirmDialog } from '@/components/domain/danger-confirm-dialog';
import { DataTable, dataTableColumns } from '@/components/domain/data-table';
import { EmptyState } from '@/components/domain/empty-state';
import { ErrorState } from '@/components/domain/error-state';
import { FilterBar, emptyValues, useSearchFilters, type FilterDef } from '@/components/domain/filter-bar';
import { PermissionGate } from '@/components/domain/permission-gate';
import { RelativeTime } from '@/components/domain/relative-time';
import { StatusGlyph } from '@/components/domain/status-badge';
import { ListPage } from '@/components/templates';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { DotsThreeIcon, PlusIcon } from '@/components/ui/glyphs';
import { Icon } from '@/components/ui/icon';
import { Skeleton } from '@/components/ui/skeleton';
import { usePermission, useProject } from '@/lib/access';
import type { Agent, AgentSessionStats } from '@/lib/api/types';
import { formatInteger } from '@/lib/format';

import { useDeleteAgent } from '../api/mutations';
import {
  agentsQuery,
  agentStatsQuery,
  latestDraft,
  latestPublished,
  modelsQuery,
  versionsQuery,
} from '../api/queries';
import { useOpenDraft } from '../api/use-open-draft';
import { CreateAgentDialog } from '../components/create-agent-dialog';
import { toDraftSpec, type DraftSpec } from '../model/spec';

/** 列表的一行：智能体加上它的版本概况与运行统计（各自加载，未到时显示骨架）。 */
interface AgentRow {
  agent: Agent;
  name: string;
  description: string;
  updatedAt: string;
  hasDraft: boolean | undefined;
  /** 当前版本的主模型名；还没有加载到时为 undefined，没有发布过时为 null。 */
  modelName: string | null | undefined;
  currentSpec: DraftSpec | null;
  stats: AgentSessionStats | null | undefined;
}

const NO_FILTERS: readonly FilterDef[] = [];
const col = dataTableColumns<AgentRow>();

function RowActions({
  row,
  onDelete,
  onCopy,
}: {
  row: AgentRow;
  onDelete: (row: AgentRow) => void;
  onCopy: (row: AgentRow) => void;
}) {
  const draft = useOpenDraft(row.agent.id);
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button
          size="icon-sm"
          variant="ghost"
          aria-label={`${row.name} 的更多操作`}
          pending={draft.pending}
          onClick={(e) => e.stopPropagation()}
        >
          <Icon glyph={DotsThreeIcon} />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="min-w-40" onClick={(e) => e.stopPropagation()}>
        <DropdownMenuItem onSelect={() => void draft.open()}>编辑定义</DropdownMenuItem>
        <DropdownMenuItem disabled={!row.currentSpec} onSelect={() => onCopy(row)}>
          复制为新智能体
        </DropdownMenuItem>
        <DropdownMenuSeparator />
        <DropdownMenuItem variant="danger" onSelect={() => onDelete(row)}>
          删除
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

/**
 * 智能体列表（列表页模板）：名称、说明、当前版本与草稿、主模型、使用中的会话、24 小时失败率、更新时间。行操作在"更多"菜单里，
 * 删除要求输入名称确认，并写明使用中的会话将无法继续。搜索在本页按名称、说明与 ID 过滤（项目内的智能体是几十个的量级）。
 */
export function AgentListPage() {
  const { tenantId, projectId } = useProject();
  const scope = { tenantId, projectId };
  const navigate = useNavigate();
  const write = usePermission('agent_write', '管理智能体');
  const [filters, setFilters] = useSearchFilters(NO_FILTERS);
  const [creating, setCreating] = useState(false);
  const [copying, setCopying] = useState<{ name: string; spec: DraftSpec } | null>(null);
  const [deleting, setDeleting] = useState<AgentRow | null>(null);
  const remove = useDeleteAgent();

  const agents = useQuery(agentsQuery(scope));
  const stats = useQuery(agentStatsQuery(projectId));
  const models = useQuery(modelsQuery(tenantId));
  const versions = useQueries({
    queries: (agents.data ?? []).map((a) => versionsQuery(scope, a.id)),
  });

  const rows = useMemo<AgentRow[]>(
    () =>
      (agents.data ?? []).map((agent, i) => {
        const list = versions[i]?.data;
        const current = list ? latestPublished(list) : undefined;
        const spec = current ? toDraftSpec(current.spec) : null;
        const modelId = spec?.model.model_id;
        return {
          agent,
          name: agent.name,
          description: agent.description,
          updatedAt: agent.updated_at,
          hasDraft: list
            ? latestDraft(list) !== undefined && (!current || latestDraft(list)!.version > current.version)
            : undefined,
          modelName: !list
            ? undefined
            : modelId
              ? (models.data?.find((m) => m.id === modelId)?.name ?? modelId)
              : null,
          currentSpec: spec,
          stats: stats.isPending
            ? undefined
            : (stats.data?.agents.find((s) => s.agent_id === agent.id) ?? (stats.data ? null : undefined)),
        };
      }),
    [agents.data, versions, models.data, stats.isPending, stats.data],
  );

  const q = filters.q.trim().toLowerCase();
  const shown = q
    ? rows.filter((r) => [r.name, r.description, r.agent.id].some((v) => v.toLowerCase().includes(q)))
    : rows;

  const columns = useMemo(
    () =>
      col.columns([
        col.accessor('name', {
          header: '名称',
          enableHiding: false,
          sortFn: 'alphanumeric',
          meta: { width: 200 },
          cell: (c) => <span className="truncate font-medium">{c.getValue()}</span>,
        }),
        col.accessor('description', {
          header: '说明',
          enableSorting: false,
          meta: { label: '说明' },
          cell: (c) => <span className="truncate text-ink-2">{c.getValue()}</span>,
        }),
        col.display({
          id: 'version',
          header: '当前版本',
          meta: { width: 128 },
          cell: (c) => {
            const { agent, hasDraft } = c.row.original;
            return (
              <span className="flex items-center gap-2">
                {agent.current_version ? (
                  <span className="tabular-nums">v{agent.current_version.version}</span>
                ) : (
                  <span className="text-ink-2">未发布</span>
                )}
                {hasDraft && <Badge tone="accent">有草稿</Badge>}
              </span>
            );
          },
        }),
        col.accessor('modelName', {
          header: '主模型',
          enableSorting: false,
          meta: { width: 152 },
          cell: (c) => {
            const v = c.getValue();
            if (v === undefined) return <Skeleton className="h-4 w-20" />;
            return v === null ? (
              <span className="text-ink-2">无</span>
            ) : (
              <span className="truncate font-mono text-12">{v}</span>
            );
          },
        }),
        col.display({
          id: 'active',
          header: '使用中的会话',
          meta: { width: 112, align: 'right' },
          cell: (c) => {
            const s = c.row.original.stats;
            if (s === undefined)
              return stats.isError ? (
                <span className="text-ink-2">—</span>
              ) : (
                <Skeleton className="ml-auto h-4 w-8" />
              );
            return <span className="tabular-nums">{formatInteger(s?.active_sessions ?? 0)}</span>;
          },
        }),
        col.display({
          id: 'failure',
          header: '失败率',
          meta: { width: 96, align: 'right' },
          cell: (c) => {
            const s = c.row.original.stats;
            if (s === undefined)
              return stats.isError ? (
                <span className="text-ink-2">—</span>
              ) : (
                <Skeleton className="ml-auto h-4 w-10" />
              );
            const rate = s?.failure_rate ?? null;
            if (rate === null) return <span className="text-ink-2">无轮次</span>;
            const text = `${(rate * 100).toFixed(1)}%`;
            return rate >= 0.1 ? (
              <span className="inline-flex items-center gap-1 font-medium text-danger tabular-nums">
                <StatusGlyph kind="failure" />
                {text}
              </span>
            ) : (
              <span className="tabular-nums">{text}</span>
            );
          },
        }),
        col.accessor('updatedAt', {
          header: '更新',
          sortFn: 'datetime',
          meta: { width: 136 },
          cell: (c) => <RelativeTime value={c.getValue()} className="text-ink-2" />,
        }),
        col.display({
          id: 'actions',
          header: () => <span className="sr-only">操作</span>,
          enableHiding: false,
          meta: { width: 56, align: 'right', label: '操作' },
          cell: (c) =>
            write.allowed && (
              <RowActions
                row={c.row.original}
                onDelete={setDeleting}
                onCopy={(row) => row.currentSpec && setCopying({ name: row.name, spec: row.currentSpec })}
              />
            ),
        }),
      ]),
    [write.allowed, stats.isError, setDeleting, setCopying],
  );

  const activeSessions = deleting?.stats?.active_sessions ?? 0;

  return (
    <ListPage
      title="智能体"
      actions={
        <PermissionGate mode="disable" allowed={write.allowed} reason={write.reason}>
          <Button variant="primary" icon={<Icon glyph={PlusIcon} />} onClick={() => setCreating(true)}>
            新建智能体
          </Button>
        </PermissionGate>
      }
    >
      <DataTable
        label="智能体列表"
        columns={columns}
        data={shown}
        getRowId={(r) => r.agent.id}
        loading={agents.isPending}
        error={
          agents.error ? (
            <ErrorState title="无法加载智能体" error={agents.error} onRetry={() => void agents.refetch()} />
          ) : undefined
        }
        onRowClick={(r) =>
          void navigate({
            to: '/projects/$projectId/agents/$agentId',
            params: { projectId, agentId: r.agent.id },
          })
        }
        className="min-h-0 flex-1"
        empty={
          q ? (
            <EmptyState
              variant="filtered"
              title="没有名称或说明匹配的智能体"
              onClearFilters={() => setFilters(emptyValues(NO_FILTERS))}
            />
          ) : (
            <EmptyState
              variant="first-use"
              title="还没有智能体：定义模型、提示词与工具后发布，就能创建会话"
              action={
                <PermissionGate mode="disable" allowed={write.allowed} reason={write.reason}>
                  <Button
                    variant="primary"
                    icon={<Icon glyph={PlusIcon} />}
                    onClick={() => setCreating(true)}
                  >
                    新建智能体
                  </Button>
                </PermissionGate>
              }
            />
          )
        }
        toolbar={
          <FilterBar
            filters={NO_FILTERS}
            value={filters}
            onChange={setFilters}
            search={{ label: '搜索智能体', placeholder: '名称、说明或 ID' }}
            resultCount={q ? shown.length : undefined}
          />
        }
      />
      {creating && <CreateAgentDialog open onOpenChange={setCreating} />}
      {copying && (
        <CreateAgentDialog open copyFrom={copying} onOpenChange={(open) => !open && setCopying(null)} />
      )}
      <DangerConfirmDialog
        open={deleting !== null}
        onOpenChange={(open) => !open && setDeleting(null)}
        title={`删除智能体 ${deleting?.name ?? ''}`}
        confirmLabel="删除智能体"
        confirmName={deleting?.name}
        consequences={
          <>
            <p>删除后不能再用它创建会话，全部版本与草稿随之删除，不能恢复。</p>
            <p>
              正在使用它的 {formatInteger(activeSessions)}{' '}
              个会话继续使用已绑定的版本，直到归档或删除；它们的事件仍可查看。
            </p>
          </>
        }
        onConfirm={async () => {
          if (deleting) await remove.mutateAsync(deleting.agent.id);
        }}
      />
    </ListPage>
  );
}
