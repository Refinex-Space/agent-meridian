import { useMemo, useState } from 'react';

import { DangerConfirmDialog } from '@/components/domain/danger-confirm-dialog';
import { DataTable, dataTableColumns } from '@/components/domain/data-table';
import { EmptyState } from '@/components/domain/empty-state';
import { FilterBar, emptyValues, type FilterValues } from '@/components/domain/filter-bar';
import { PermissionGate } from '@/components/domain/permission-gate';
import { RelativeTime } from '@/components/domain/relative-time';
import { StatusGlyph } from '@/components/domain/status-badge';
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
import { formatInteger } from '@/lib/format';

import type { AgentRow } from '../fixtures/agent-definition';
import { ListPage } from '../templates/list-page';

export interface AgentListProps {
  agents: readonly AgentRow[];
  /** 当前用户能否新建、编辑、删除（AGENT_WRITE）。 */
  canWrite?: boolean;
}

const NO_WRITE = '管理智能体需要本项目的开发者或所有者角色，请联系项目管理员。';
const col = dataTableColumns<AgentRow>();

/**
 * 智能体列表（列表页模板的例子）：名称、当前版本与草稿、主模型、使用中的会话、24 小时失败率、更新时间；行操作在"更多"菜单里，
 * 删除要求输入名称确认，并写明使用中的会话将无法继续。
 */
export function AgentList({ agents, canWrite = true }: AgentListProps) {
  const [filters, setFilters] = useState<FilterValues>(emptyValues([]));
  const [deleting, setDeleting] = useState<AgentRow | null>(null);
  const rows = useMemo(
    () => agents.filter((a) => !filters.q || a.name.includes(filters.q) || a.id.includes(filters.q)),
    [agents, filters.q],
  );

  const columns = useMemo(
    () =>
      col.columns([
        col.accessor('name', {
          header: '名称',
          enableHiding: false,
          sortFn: 'alphanumeric',
          cell: (c) => <span className="truncate font-medium">{c.getValue()}</span>,
        }),
        col.accessor('version', {
          header: '当前版本',
          enableSorting: false,
          meta: { width: 144 },
          cell: (c) => (
            <span className="flex items-center gap-2">
              <span className="tabular-nums">v{c.getValue()}</span>
              {c.row.original.hasDraft && <Badge tone="accent">有草稿</Badge>}
            </span>
          ),
        }),
        col.accessor('modelName', {
          header: '主模型',
          enableSorting: false,
          meta: { width: 152 },
          cell: (c) => <span className="truncate font-mono text-12">{c.getValue()}</span>,
        }),
        col.accessor('activeSessions', {
          header: '使用中的会话',
          sortFn: 'basic',
          meta: { width: 128, align: 'right' },
          cell: (c) => <span className="tabular-nums">{formatInteger(c.getValue())}</span>,
        }),
        col.accessor('failureRate', {
          header: '失败率',
          sortFn: 'basic',
          meta: { width: 104, align: 'right' },
          cell: (c) => {
            const rate = c.getValue();
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
          meta: { width: 120 },
          cell: (c) => <RelativeTime value={c.getValue()} className="text-ink-2" />,
        }),
        col.display({
          id: 'actions',
          header: () => <span className="sr-only">操作</span>,
          enableHiding: false,
          meta: { width: 56, align: 'right', label: '操作' },
          cell: (c) =>
            canWrite && (
              <DropdownMenu>
                <DropdownMenuTrigger asChild>
                  <Button
                    size="icon-sm"
                    variant="ghost"
                    aria-label={`${c.row.original.name} 的更多操作`}
                    onClick={(e) => e.stopPropagation()}
                  >
                    <Icon glyph={DotsThreeIcon} />
                  </Button>
                </DropdownMenuTrigger>
                <DropdownMenuContent align="end" className="min-w-40">
                  <DropdownMenuItem>编辑定义</DropdownMenuItem>
                  <DropdownMenuItem>复制为新智能体</DropdownMenuItem>
                  <DropdownMenuSeparator />
                  <DropdownMenuItem variant="danger" onSelect={() => setDeleting(c.row.original)}>
                    删除
                  </DropdownMenuItem>
                </DropdownMenuContent>
              </DropdownMenu>
            ),
        }),
      ]),
    [canWrite],
  );

  return (
    <ListPage
      title="智能体"
      actions={
        <PermissionGate allowed={canWrite} mode="disable" reason={NO_WRITE}>
          <Button variant="primary" icon={<Icon glyph={PlusIcon} />}>
            新建智能体
          </Button>
        </PermissionGate>
      }
    >
      <DataTable
        label="智能体列表"
        columns={columns}
        data={rows}
        getRowId={(a) => a.id}
        onRowClick={() => undefined}
        className="min-h-0 flex-1"
        empty={
          filters.q ? (
            <EmptyState
              variant="filtered"
              title="没有名字匹配的智能体"
              onClearFilters={() => setFilters(emptyValues([]))}
            />
          ) : (
            <EmptyState
              variant="first-use"
              title="还没有智能体：定义模型、提示词与工具后发布，就能创建会话"
            />
          )
        }
        toolbar={
          <FilterBar
            filters={[]}
            value={filters}
            onChange={setFilters}
            search={{ label: '搜索智能体', placeholder: '名称或 ID' }}
            resultCount={rows.length}
          />
        }
      />
      <DangerConfirmDialog
        open={deleting !== null}
        onOpenChange={(open) => !open && setDeleting(null)}
        title={`删除智能体 ${deleting?.name ?? ''}`}
        confirmLabel="删除智能体"
        confirmName={deleting?.name}
        consequences={
          <>
            <p>
              删除后，该智能体的 {deleting?.activeSessions ?? 0}{' '}
              个使用中的会话将无法继续，全部版本与草稿一并删除，不能恢复。
            </p>
            <p>已结束的会话与它们的事件仍可查看。</p>
          </>
        }
        onConfirm={() => new Promise((resolve) => window.setTimeout(resolve, 600))}
      />
    </ListPage>
  );
}
