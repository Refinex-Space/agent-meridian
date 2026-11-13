import { useQuery } from '@tanstack/react-query';
import { useMemo, useState, type ReactNode } from 'react';

import { DangerConfirmDialog } from '@/components/domain/danger-confirm-dialog';
import { DataTable, dataTableColumns } from '@/components/domain/data-table';
import { EmptyState } from '@/components/domain/empty-state';
import { ErrorState } from '@/components/domain/error-state';
import { InUseDialog } from '@/components/domain/reference-list';
import { connectivityStatus, StatusBadge } from '@/components/domain/status-badge';
import { Button } from '@/components/ui/button';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { DotsThreeIcon, InfoIcon } from '@/components/ui/glyphs';
import { Icon } from '@/components/ui/icon';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import { secretsQuery } from '@/features/settings';
import { usePermission, useProject } from '@/lib/access';
import { inUseReferences } from '@/lib/api/errors';
import type { ProviderAccount, ResourceReference } from '@/lib/api/types';
import { formatDateTime, formatDuration } from '@/lib/format';

import { providerAccountsQuery, useDeleteProviderAccount, useTestProviderAccount } from '../api/queries';
import { PROVIDER_LABEL } from '../model/catalog';

import { ProviderDialog } from './provider-dialog';

const col = dataTableColumns<ProviderAccount>();

/** 连通性：状态符号与成功时的延迟；测试时间与失败原因放在提示里。 */
function Connectivity({ account }: { account: ProviderAccount }) {
  const check = account.last_check;
  const { kind, label } = connectivityStatus(check);
  const badge = (
    <span className="flex items-center gap-2">
      <StatusBadge of="custom" kind={kind} label={label} />
      {check?.status === 'ok' && (
        <span className="text-12 text-ink-2 tabular-nums">{formatDuration(check.latency_ms)}</span>
      )}
    </span>
  );
  if (!check) return badge;
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <button type="button" className="rounded-control text-left">
          {badge}
        </button>
      </TooltipTrigger>
      <TooltipContent className="flex max-w-80 flex-col gap-1">
        <span>测试于 {formatDateTime(check.checked_at)}</span>
        {check.message && <span>{check.message}</span>}
      </TooltipContent>
    </Tooltip>
  );
}

export interface ProviderTableProps {
  /** 首次使用的空态里的主操作（"新建供应商账号"）。 */
  createButton: ReactNode;
}

/**
 * 供应商账号：名称、类型、端点、凭据、连通性与测试时间；行内"测试连通性"列一次模型清单（不产生用量），结果就地更新在这一行。
 */
export function ProviderTable({ createButton }: ProviderTableProps) {
  const { tenantId } = useProject();
  const write = usePermission('model_write', '修改供应商账号或测试连通性');
  const readSecrets = usePermission('secret_read', '查看凭据');
  const accounts = useQuery(providerAccountsQuery(tenantId));
  const secrets = useQuery({ ...secretsQuery(tenantId), enabled: readSecrets.allowed });
  const test = useTestProviderAccount();
  const remove = useDeleteProviderAccount();
  const [editing, setEditing] = useState<ProviderAccount | null>(null);
  const [deleting, setDeleting] = useState<ProviderAccount | null>(null);
  const [inUse, setInUse] = useState<{ name: string; references: ResourceReference[] } | null>(null);
  const secretNames = useMemo(() => new Map((secrets.data ?? []).map((s) => [s.id, s.name])), [secrets.data]);
  const testing = test.isPending ? test.variables : null;
  const runTest = test.mutate;

  const columns = useMemo(
    () =>
      col.columns([
        col.accessor('name', {
          header: '名称',
          enableHiding: false,
          sortFn: 'alphanumeric',
          meta: { width: 176 },
          cell: (c) => <span className="truncate font-medium">{c.getValue()}</span>,
        }),
        col.accessor('provider', {
          header: '类型',
          enableSorting: false,
          meta: { width: 104 },
          cell: (c) => PROVIDER_LABEL[c.getValue()],
        }),
        col.accessor('base_url', {
          header: '端点',
          enableSorting: false,
          meta: { label: '端点' },
          cell: (c) => {
            const url = c.getValue();
            return url ? (
              <span className="truncate font-mono text-12 text-ink-2">{url}</span>
            ) : (
              <span className="text-ink-2">官方端点</span>
            );
          },
        }),
        col.accessor('secret_id', {
          header: '凭据',
          enableSorting: false,
          meta: { width: 152 },
          cell: (c) => {
            const id = c.getValue();
            if (!id) return <span className="text-ink-2">不需要</span>;
            const name = secretNames.get(id);
            return name ? (
              <span className="truncate">{name}</span>
            ) : (
              <span className="truncate font-mono text-12">{id}</span>
            );
          },
        }),
        col.display({
          id: 'connectivity',
          header: () => (
            <span className="flex items-center gap-1">
              连通性
              <Tooltip>
                <TooltipTrigger asChild>
                  <button
                    type="button"
                    aria-label="连通性测试的说明"
                    className="inline-flex rounded-control text-ink-2 hover:text-ink"
                  >
                    <Icon glyph={InfoIcon} />
                  </button>
                </TooltipTrigger>
                <TooltipContent className="max-w-80 font-normal">
                  用账号的端点与凭据列一次模型清单，不调用模型、不产生用量。要验证某个模型，在模型目录里测试它。
                </TooltipContent>
              </Tooltip>
            </span>
          ),
          meta: { width: 168, label: '连通性' },
          cell: (c) => <Connectivity account={c.row.original} />,
        }),
        col.display({
          id: 'actions',
          header: () => <span className="sr-only">操作</span>,
          enableHiding: false,
          meta: { width: 152, align: 'right', label: '操作' },
          cell: (c) =>
            write.allowed && (
              <span className="flex items-center justify-end gap-1">
                <Button
                  size="sm"
                  pending={testing === c.row.original.id}
                  disabled={testing !== null && testing !== c.row.original.id}
                  onClick={() => runTest(c.row.original.id)}
                >
                  测试连通性
                </Button>
                <DropdownMenu>
                  <DropdownMenuTrigger asChild>
                    <Button size="icon-sm" variant="ghost" aria-label={`${c.row.original.name} 的更多操作`}>
                      <Icon glyph={DotsThreeIcon} />
                    </Button>
                  </DropdownMenuTrigger>
                  <DropdownMenuContent align="end" className="min-w-40">
                    <DropdownMenuItem onSelect={() => setEditing(c.row.original)}>修改</DropdownMenuItem>
                    <DropdownMenuSeparator />
                    <DropdownMenuItem variant="danger" onSelect={() => setDeleting(c.row.original)}>
                      删除
                    </DropdownMenuItem>
                  </DropdownMenuContent>
                </DropdownMenu>
              </span>
            ),
        }),
      ]),
    [write.allowed, secretNames, testing, runTest],
  );

  return (
    <>
      <DataTable
        label="供应商账号"
        columns={columns}
        data={accounts.data ?? []}
        getRowId={(a) => a.id}
        loading={accounts.isPending}
        error={
          accounts.error ? (
            <ErrorState
              title="无法加载供应商账号"
              error={accounts.error}
              onRetry={() => void accounts.refetch()}
            />
          ) : undefined
        }
        className="min-h-0 flex-1"
        empty={
          <EmptyState
            variant="first-use"
            title="还没有供应商账号：写好端点与凭据，再给它添加模型"
            action={createButton}
          />
        }
      />
      {test.error ? (
        <div className="pt-3">
          <ErrorState size="inline" title="测试没有完成" error={test.error} />
        </div>
      ) : null}
      {editing && (
        <ProviderDialog
          key={editing.id}
          open
          onOpenChange={(open) => !open && setEditing(null)}
          account={editing}
        />
      )}
      <InUseDialog
        name={inUse?.name ?? null}
        references={inUse?.references ?? []}
        nextStep="先删除或移走这个账号下的模型，再删除账号。"
        onClose={() => setInUse(null)}
      />
      <DangerConfirmDialog
        open={deleting !== null}
        onOpenChange={(open) => !open && setDeleting(null)}
        title={`删除供应商账号 ${deleting?.name ?? ''}`}
        confirmLabel="删除账号"
        confirmName={deleting?.name}
        consequences={<p>账号下还有模型时不能删除。删除后它引用的凭据不受影响。</p>}
        onConfirm={async () => {
          if (!deleting) return;
          try {
            await remove.mutateAsync(deleting.id);
          } catch (error) {
            const references = inUseReferences(error);
            if (!references) throw error;
            setInUse({ name: deleting.name, references });
          }
        }}
      />
    </>
  );
}
