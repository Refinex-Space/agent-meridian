import { useQuery } from '@tanstack/react-query';
import { useMemo, useState, type ReactNode } from 'react';

import { DangerConfirmDialog } from '@/components/domain/danger-confirm-dialog';
import { DataTable, dataTableColumns } from '@/components/domain/data-table';
import { EmptyState } from '@/components/domain/empty-state';
import { ErrorState } from '@/components/domain/error-state';
import { FilterBar, emptyValues, useSearchFilters, type FilterDef } from '@/components/domain/filter-bar';
import { PermissionGate } from '@/components/domain/permission-gate';
import { InUseDialog } from '@/components/domain/reference-list';
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
import { usePermission, useProject } from '@/lib/access';
import { inUseReferences } from '@/lib/api/errors';
import type { CatalogModel, ModelCapabilities, ResourceReference } from '@/lib/api/types';
import { formatTokens } from '@/lib/format';

import { catalogModelsQuery, priceBooksQuery, providerAccountsQuery, useDeleteModel } from '../api/queries';
import { CAPABILITY_LABEL, MODEL_KIND_LABEL } from '../model/catalog';

import { ModelSheet } from './model-sheet';
import { ModelTestDialog } from './model-test-dialog';

const col = dataTableColumns<CatalogModel>();

const KIND_FILTER: readonly FilterDef[] = [
  {
    key: 'kind',
    label: '种类',
    multiple: true,
    options: (['chat', 'embedding', 'rerank'] as const).map((k) => ({
      value: k,
      label: MODEL_KIND_LABEL[k],
    })),
  },
];

function capabilityText(capabilities: ModelCapabilities): string {
  const on = (Object.keys(CAPABILITY_LABEL) as (keyof ModelCapabilities)[]).filter((k) => capabilities[k]);
  return on.map((k) => CAPABILITY_LABEL[k]).join('、');
}

export interface CatalogTableProps {
  /** 首次使用的空态里的主操作（"添加模型"）。 */
  createButton: ReactNode;
}

/**
 * 模型目录：名称、种类、供应商账号、上游模型名、上下文窗口、默认最大输出、能力与价格表；行内"测试"经直通接口调用一次模型，
 * 会产生少量费用（列头的提示写明）。
 */
export function CatalogTable({ createButton }: CatalogTableProps) {
  const { tenantId } = useProject();
  const write = usePermission('model_write', '修改或删除模型');
  const invoke = usePermission('model_invoke', '测试模型');
  const models = useQuery(catalogModelsQuery(tenantId));
  const accounts = useQuery(providerAccountsQuery(tenantId));
  const priceBooks = useQuery(priceBooksQuery(tenantId));
  const remove = useDeleteModel();
  const [filters, setFilters] = useSearchFilters(KIND_FILTER);
  const [editing, setEditing] = useState<CatalogModel | null>(null);
  const [testing, setTesting] = useState<CatalogModel | null>(null);
  const [deleting, setDeleting] = useState<CatalogModel | null>(null);
  const [inUse, setInUse] = useState<{ name: string; references: ResourceReference[] } | null>(null);

  const accountNames = useMemo(
    () => new Map((accounts.data ?? []).map((a) => [a.id, a.name])),
    [accounts.data],
  );
  const priceNames = useMemo(
    () => new Map((priceBooks.data ?? []).map((p) => [p.id, p.name])),
    [priceBooks.data],
  );

  const q = filters.q.trim().toLowerCase();
  const kinds = filters.filters['kind'] ?? [];
  const rows = (models.data ?? []).filter(
    (m) =>
      (kinds.length === 0 || kinds.includes(m.kind)) &&
      (!q || m.name.toLowerCase().includes(q) || m.upstream_model.toLowerCase().includes(q)),
  );

  const columns = useMemo(
    () =>
      col.columns([
        col.accessor('name', {
          header: '名称',
          enableHiding: false,
          sortFn: 'alphanumeric',
          meta: { width: 200 },
          cell: (c) => <span className="truncate font-mono text-12 font-medium">{c.getValue()}</span>,
        }),
        col.accessor('kind', {
          header: '种类',
          enableSorting: false,
          meta: { width: 104 },
          cell: (c) => MODEL_KIND_LABEL[c.getValue()],
        }),
        col.accessor('provider_account_id', {
          header: '供应商账号',
          enableSorting: false,
          meta: { width: 160 },
          cell: (c) => <span className="truncate">{accountNames.get(c.getValue()) ?? c.getValue()}</span>,
        }),
        col.accessor('upstream_model', {
          header: '上游模型',
          enableSorting: false,
          meta: { label: '上游模型' },
          cell: (c) => <span className="truncate font-mono text-12 text-ink-2">{c.getValue()}</span>,
        }),
        col.accessor('context_window', {
          header: '上下文',
          sortFn: 'basic',
          meta: { width: 88, align: 'right' },
          cell: (c) => <span className="tabular-nums">{formatTokens(c.getValue())}</span>,
        }),
        col.accessor('max_output_tokens', {
          header: '默认最大输出',
          sortFn: 'basic',
          meta: { width: 112, align: 'right' },
          cell: (c) => <span className="tabular-nums">{formatTokens(c.getValue())}</span>,
        }),
        col.accessor('capabilities', {
          header: '能力',
          enableSorting: false,
          meta: { width: 176 },
          cell: (c) => {
            const text = c.row.original.kind === 'chat' ? capabilityText(c.getValue()) : '';
            return text ? <span className="truncate">{text}</span> : <span className="text-ink-2">无</span>;
          },
        }),
        col.accessor('price_book_id', {
          header: '价格表',
          enableSorting: false,
          meta: { width: 160 },
          cell: (c) => {
            const id = c.getValue();
            return id ? (
              <span className="truncate">{priceNames.get(id) ?? id}</span>
            ) : (
              <span className="text-ink-2">不计价</span>
            );
          },
        }),
        col.display({
          id: 'actions',
          header: () => (
            <span className="flex items-center justify-end gap-1">
              <span className="sr-only">操作</span>
              <Tooltip>
                <TooltipTrigger asChild>
                  <button
                    type="button"
                    aria-label="测试模型的说明"
                    className="inline-flex rounded-control text-ink-2 hover:text-ink"
                  >
                    <Icon glyph={InfoIcon} />
                  </button>
                </TooltipTrigger>
                <TooltipContent className="max-w-80 font-normal">
                  测试会调用一次模型（至多十几个
                  token），产生少量费用，计入当前项目的用量。重排模型没有直通接口，用知识库的检索测试验证。
                </TooltipContent>
              </Tooltip>
            </span>
          ),
          enableHiding: false,
          meta: { width: 112, align: 'right', label: '操作' },
          cell: (c) => {
            const model = c.row.original;
            return (
              <span className="flex items-center justify-end gap-1">
                {model.kind !== 'rerank' && (
                  <PermissionGate mode="disable" allowed={invoke.allowed} reason={invoke.reason}>
                    <Button size="sm" onClick={() => setTesting(model)}>
                      测试
                    </Button>
                  </PermissionGate>
                )}
                {write.allowed && (
                  <DropdownMenu>
                    <DropdownMenuTrigger asChild>
                      <Button size="icon-sm" variant="ghost" aria-label={`${model.name} 的更多操作`}>
                        <Icon glyph={DotsThreeIcon} />
                      </Button>
                    </DropdownMenuTrigger>
                    <DropdownMenuContent align="end" className="min-w-40">
                      <DropdownMenuItem onSelect={() => setEditing(model)}>修改</DropdownMenuItem>
                      <DropdownMenuSeparator />
                      <DropdownMenuItem variant="danger" onSelect={() => setDeleting(model)}>
                        删除
                      </DropdownMenuItem>
                    </DropdownMenuContent>
                  </DropdownMenu>
                )}
              </span>
            );
          },
        }),
      ]),
    [accountNames, priceNames, write.allowed, invoke.allowed, invoke.reason],
  );

  return (
    <>
      <DataTable
        label="模型目录"
        columns={columns}
        data={rows}
        getRowId={(m) => m.id}
        loading={models.isPending}
        error={
          models.error ? (
            <ErrorState title="无法加载模型目录" error={models.error} onRetry={() => void models.refetch()} />
          ) : undefined
        }
        className="min-h-0 flex-1"
        empty={
          q || kinds.length > 0 ? (
            <EmptyState
              variant="filtered"
              title="没有匹配的模型"
              onClearFilters={() => setFilters(emptyValues(KIND_FILTER))}
            />
          ) : (
            <EmptyState
              variant="first-use"
              title="还没有模型：选供应商账号，写上游模型名与上下文窗口"
              action={createButton}
            />
          )
        }
        toolbar={
          <FilterBar
            filters={KIND_FILTER}
            value={filters}
            onChange={setFilters}
            search={{ label: '搜索模型', placeholder: '名称或上游模型名' }}
            resultCount={q || kinds.length > 0 ? rows.length : undefined}
          />
        }
      />
      {editing && (
        <ModelSheet
          key={editing.id}
          open
          onOpenChange={(open) => !open && setEditing(null)}
          model={editing}
        />
      )}
      <ModelTestDialog key={testing?.id ?? 'none'} model={testing} onClose={() => setTesting(null)} />
      <InUseDialog
        name={inUse?.name ?? null}
        references={inUse?.references ?? []}
        nextStep="先让引用它的模型换一个备用模型、智能体发布不用它的版本，再删除。"
        onClose={() => setInUse(null)}
      />
      <DangerConfirmDialog
        open={deleting !== null}
        onOpenChange={(open) => !open && setDeleting(null)}
        title={`删除模型 ${deleting?.name ?? ''}`}
        confirmLabel="删除模型"
        confirmName={deleting?.name}
        consequences={
          <>
            <p>
              删除后智能体与直通接口不能再用它；被其他模型作为备用模型、或被已发布的智能体版本引用时不能删除。
            </p>
            <p>已经记下的用量不受影响。</p>
          </>
        }
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
