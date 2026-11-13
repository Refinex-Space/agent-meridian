import { useQuery, useSuspenseQuery } from '@tanstack/react-query';
import { useCallback, useMemo, useState, type ReactNode } from 'react';

import { DangerConfirmDialog } from '@/components/domain/danger-confirm-dialog';
import { DataTable, dataTableColumns } from '@/components/domain/data-table';
import { EmptyState } from '@/components/domain/empty-state';
import { ErrorState } from '@/components/domain/error-state';
import { FilterBar, emptyValues, useSearchFilters, type FilterDef } from '@/components/domain/filter-bar';
import { PermissionGate } from '@/components/domain/permission-gate';
import { InUseDialog, summarizeReferences } from '@/components/domain/reference-list';
import { RelativeTime } from '@/components/domain/relative-time';
import { Button } from '@/components/ui/button';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { DotsThreeIcon } from '@/components/ui/glyphs';
import { Icon } from '@/components/ui/icon';
import { meQuery, projectsQuery, usePermission, useProject } from '@/lib/access';
import type { Secret } from '@/lib/api/types';

import { secretsQuery, useDeleteSecret } from '../api/secrets';
import { RotateSecretDialog } from './secret-dialogs';

const NO_FILTERS: readonly FilterDef[] = [];
const col = dataTableColumns<Secret>();

export interface SecretTableProps {
  /** 页头的主操作（"写入凭据"），也放在首次使用的空态里。 */
  createButton: ReactNode;
}

/**
 * 凭据列表（设置的"凭据"标签页，ADR-0056）：名称、范围、版本、引用方、最后使用与创建时间；行操作是轮换与删除。凭据只写不读：
 * 写入、轮换之后任何地方都看不到明文；仍被引用的凭据不能删除，删除时直接列出引用方。没有查看权限时说明原因。
 */
export function SecretTable({ createButton }: SecretTableProps) {
  const read = usePermission('secret_read', '查看凭据');
  if (!read.allowed)
    return <PermissionGate mode="explain" title="没有权限查看凭据" allowed={false} reason={read.reason} />;
  return <SecretList createButton={createButton} />;
}

function SecretList({ createButton }: SecretTableProps) {
  const { tenantId, projectId } = useProject();
  const write = usePermission('secret_write', '写入、轮换或删除凭据');
  const me = useSuspenseQuery(meQuery()).data;
  const projects = useQuery(projectsQuery(me));
  const secrets = useQuery(secretsQuery(tenantId));
  const remove = useDeleteSecret();
  const [filters, setFilters] = useSearchFilters(NO_FILTERS);
  const [rotating, setRotating] = useState<Secret | null>(null);
  const [deleting, setDeleting] = useState<Secret | null>(null);

  const projectNames = useMemo(
    () =>
      new Map(
        (projects.data ?? [])
          .find((g) => g.tenant_id === tenantId)
          ?.projects.map((p) => [p.id, p.name] as const) ?? [],
      ),
    [projects.data, tenantId],
  );
  const scopeText = useCallback(
    (secret: Secret) =>
      secret.project_id === null
        ? '租户内通用'
        : secret.project_id === projectId
          ? '本项目'
          : (projectNames.get(secret.project_id) ?? '其他项目'),
    [projectNames, projectId],
  );

  const q = filters.q.trim().toLowerCase();
  const rows = (secrets.data ?? []).filter(
    (s) => !q || s.name.toLowerCase().includes(q) || s.id.toLowerCase().includes(q),
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
        col.display({
          id: 'scope',
          header: '范围',
          meta: { width: 144 },
          cell: (c) => <span className="truncate">{scopeText(c.row.original)}</span>,
        }),
        col.accessor('version', {
          header: '版本',
          sortFn: 'basic',
          meta: { width: 72, align: 'right' },
          cell: (c) => <span className="tabular-nums">{c.getValue()}</span>,
        }),
        col.display({
          id: 'references',
          header: '引用方',
          meta: { label: '引用方' },
          cell: (c) => {
            const refs = c.row.original.references;
            return refs.length === 0 ? (
              <span className="text-ink-2">未被引用</span>
            ) : (
              <span className="truncate">{summarizeReferences(refs)}</span>
            );
          },
        }),
        col.accessor('last_used_at', {
          header: '最后使用',
          sortFn: 'datetime',
          meta: { width: 136 },
          cell: (c) => {
            const value = c.getValue();
            return value ? (
              <RelativeTime value={value} className="text-ink-2" />
            ) : (
              <span className="text-ink-2">从未使用</span>
            );
          },
        }),
        col.accessor('created_at', {
          header: '创建',
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
                  <Button size="icon-sm" variant="ghost" aria-label={`${c.row.original.name} 的更多操作`}>
                    <Icon glyph={DotsThreeIcon} />
                  </Button>
                </DropdownMenuTrigger>
                <DropdownMenuContent align="end" className="min-w-40">
                  <DropdownMenuItem onSelect={() => setRotating(c.row.original)}>轮换</DropdownMenuItem>
                  <DropdownMenuSeparator />
                  <DropdownMenuItem variant="danger" onSelect={() => setDeleting(c.row.original)}>
                    删除
                  </DropdownMenuItem>
                </DropdownMenuContent>
              </DropdownMenu>
            ),
        }),
      ]),
    [write.allowed, scopeText],
  );

  const inUse = deleting !== null && deleting.references.length > 0;
  return (
    <>
      <DataTable
        label="凭据列表"
        columns={columns}
        data={rows}
        getRowId={(s) => s.id}
        loading={secrets.isPending}
        error={
          secrets.error ? (
            <ErrorState title="无法加载凭据" error={secrets.error} onRetry={() => void secrets.refetch()} />
          ) : undefined
        }
        className="min-h-0 flex-1"
        empty={
          q ? (
            <EmptyState
              variant="filtered"
              title="没有名称匹配的凭据"
              onClearFilters={() => setFilters(emptyValues(NO_FILTERS))}
            />
          ) : (
            <EmptyState
              variant="first-use"
              title="还没有凭据：写入供应商的 API Key 或 MCP 服务的令牌，之后只能轮换或删除"
              action={createButton}
            />
          )
        }
        toolbar={
          <FilterBar
            filters={NO_FILTERS}
            value={filters}
            onChange={setFilters}
            search={{ label: '搜索凭据', placeholder: '名称或 ID' }}
            resultCount={q ? rows.length : undefined}
          />
        }
      />
      <RotateSecretDialog secret={rotating} onOpenChange={(open) => !open && setRotating(null)} />
      <InUseDialog
        name={inUse ? deleting.name : null}
        references={deleting?.references ?? []}
        nextStep="先让下面的资源改用其他凭据，或删除它们，再删除这个凭据。"
        onClose={() => setDeleting(null)}
      />
      <DangerConfirmDialog
        open={deleting !== null && !inUse}
        onOpenChange={(open) => !open && setDeleting(null)}
        title={`删除凭据 ${deleting?.name ?? ''}`}
        confirmLabel="删除凭据"
        confirmName={deleting?.name}
        consequences={<p>密文随之删除，不能恢复；之后要用同样的值只能重新写入。</p>}
        onConfirm={async () => {
          if (deleting) await remove.mutateAsync(deleting.id);
        }}
      />
    </>
  );
}
