import { useQuery } from '@tanstack/react-query';
import { Link, useNavigate } from '@tanstack/react-router';
import { useMemo, useState } from 'react';

import { DangerConfirmDialog } from '@/components/domain/danger-confirm-dialog';
import { DataTable, dataTableColumns } from '@/components/domain/data-table';
import { EmptyState } from '@/components/domain/empty-state';
import { ErrorState } from '@/components/domain/error-state';
import { FilterBar, emptyValues, useSearchFilters, type FilterDef } from '@/components/domain/filter-bar';
import { PermissionGate } from '@/components/domain/permission-gate';
import { RelativeTime } from '@/components/domain/relative-time';
import { ListPage } from '@/components/templates';
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
import { usePermission, useProject } from '@/lib/access';
import type { Environment } from '@/lib/api/types';

import { environmentsQuery, useDeleteEnvironment } from '../api/queries';
import { sizeText, TIER_LABEL } from '../model/environment';

const NO_FILTERS: readonly FilterDef[] = [];
const col = dataTableColumns<Environment>();

/**
 * 环境列表（列表页模板）：名称、档位、规格、出网、当前修订、更新时间；行操作是编辑与删除（输入名称确认，写明对会话的影响）。
 */
export function EnvironmentListPage() {
  const read = usePermission('agent_read', '查看环境');
  if (!read.allowed)
    return (
      <ListPage title="环境">
        <PermissionGate mode="explain" title="没有权限查看环境" allowed={false} reason={read.reason} />
      </ListPage>
    );
  return <EnvironmentList />;
}

function EnvironmentList() {
  const { tenantId, projectId } = useProject();
  const navigate = useNavigate();
  const write = usePermission('agent_write', '管理环境');
  const [filters, setFilters] = useSearchFilters(NO_FILTERS);
  const [deleting, setDeleting] = useState<Environment | null>(null);
  const environments = useQuery(environmentsQuery({ tenantId, projectId }));
  const remove = useDeleteEnvironment();

  const q = filters.q.trim().toLowerCase();
  const rows = (environments.data ?? []).filter(
    (e) => !q || e.name.toLowerCase().includes(q) || e.id.toLowerCase().includes(q),
  );

  const columns = useMemo(
    () =>
      col.columns([
        col.accessor('name', {
          header: '名称',
          enableHiding: false,
          sortFn: 'alphanumeric',
          meta: { width: 240 },
          cell: (c) => <span className="truncate font-medium">{c.getValue()}</span>,
        }),
        col.accessor('tier', {
          header: '档位',
          enableSorting: false,
          meta: { width: 104 },
          cell: (c) => TIER_LABEL[c.getValue()],
        }),
        col.accessor('size', {
          header: '规格',
          enableSorting: false,
          meta: { label: '规格' },
          cell: (c) => sizeText(c.getValue()) ?? <span className="text-ink-2">无</span>,
        }),
        col.display({
          id: 'egress',
          header: '出网',
          meta: { width: 128 },
          cell: (c) => {
            const env = c.row.original;
            if (env.tier === 'none') return <span className="text-ink-2">不适用</span>;
            return env.egress_allowlist.length === 0 ? '不出网' : `${env.egress_allowlist.length} 个域名`;
          },
        }),
        col.accessor('revision', {
          header: '修订',
          sortFn: 'basic',
          meta: { width: 80, align: 'right' },
          cell: (c) => <span className="tabular-nums">{c.getValue()}</span>,
        }),
        col.accessor('updated_at', {
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
                <DropdownMenuContent align="end" className="min-w-40" onClick={(e) => e.stopPropagation()}>
                  <DropdownMenuItem asChild>
                    <Link
                      to="/projects/$projectId/environments/$environmentId"
                      params={{ projectId, environmentId: c.row.original.id }}
                    >
                      编辑
                    </Link>
                  </DropdownMenuItem>
                  <DropdownMenuSeparator />
                  <DropdownMenuItem variant="danger" onSelect={() => setDeleting(c.row.original)}>
                    删除
                  </DropdownMenuItem>
                </DropdownMenuContent>
              </DropdownMenu>
            ),
        }),
      ]),
    [write.allowed, projectId, setDeleting],
  );

  const newButton = (
    <PermissionGate mode="disable" allowed={write.allowed} reason={write.reason}>
      <Button variant="primary" asChild>
        <Link to="/projects/$projectId/environments/new" params={{ projectId }}>
          <Icon glyph={PlusIcon} />
          新建环境
        </Link>
      </Button>
    </PermissionGate>
  );

  return (
    <ListPage title="环境" actions={newButton}>
      <DataTable
        label="环境列表"
        columns={columns}
        data={rows}
        getRowId={(e) => e.id}
        loading={environments.isPending}
        error={
          environments.error ? (
            <ErrorState
              title="无法加载环境"
              error={environments.error}
              onRetry={() => void environments.refetch()}
            />
          ) : undefined
        }
        onRowClick={(e) =>
          void navigate({
            to: '/projects/$projectId/environments/$environmentId',
            params: { projectId, environmentId: e.id },
          })
        }
        className="min-h-0 flex-1"
        empty={
          q ? (
            <EmptyState
              variant="filtered"
              title="没有名称匹配的环境"
              onClearFilters={() => setFilters(emptyValues(NO_FILTERS))}
            />
          ) : (
            <EmptyState
              variant="first-use"
              title="还没有环境：选择执行档位与出网白名单，创建会话时使用"
              action={newButton}
            />
          )
        }
        toolbar={
          <FilterBar
            filters={NO_FILTERS}
            value={filters}
            onChange={setFilters}
            search={{ label: '搜索环境', placeholder: '名称或 ID' }}
            resultCount={q ? rows.length : undefined}
          />
        }
      />
      <DangerConfirmDialog
        open={deleting !== null}
        onOpenChange={(open) => !open && setDeleting(null)}
        title={`删除环境 ${deleting?.name ?? ''}`}
        confirmLabel="删除环境"
        confirmName={deleting?.name}
        consequences={
          <>
            <p>删除后不能再用它创建会话，修订历史随之删除，不能恢复。</p>
            <p>正在使用它的会话继续使用创建时的修订，直到归档或删除。</p>
          </>
        }
        onConfirm={async () => {
          if (deleting) await remove.mutateAsync(deleting.id);
        }}
      />
    </ListPage>
  );
}
