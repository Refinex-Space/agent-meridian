import { useQuery } from '@tanstack/react-query';
import { useNavigate } from '@tanstack/react-router';
import { useMemo, type ReactNode } from 'react';

import { DataTable, dataTableColumns } from '@/components/domain/data-table';
import { EmptyState } from '@/components/domain/empty-state';
import { ErrorState } from '@/components/domain/error-state';
import { RelativeTime } from '@/components/domain/relative-time';
import { useProject } from '@/lib/access';
import type { PriceBook } from '@/lib/api/types';

import { catalogModelsQuery, priceBooksQuery } from '../api/queries';

const col = dataTableColumns<PriceBook>();

export interface PriceBookTableProps {
  /** 首次使用的空态里的主操作（"新建价格表"）。 */
  createButton: ReactNode;
}

/** 价格表：名称、币种、当前版本与它的输入、输出单价、生效时间、用它计价的模型数；点行进入版本历史。 */
export function PriceBookTable({ createButton }: PriceBookTableProps) {
  const { tenantId, projectId } = useProject();
  const navigate = useNavigate();
  const priceBooks = useQuery(priceBooksQuery(tenantId));
  const models = useQuery(catalogModelsQuery(tenantId));
  const usage = useMemo(() => {
    const counts = new Map<string, number>();
    for (const m of models.data ?? [])
      if (m.price_book_id) counts.set(m.price_book_id, (counts.get(m.price_book_id) ?? 0) + 1);
    return counts;
  }, [models.data]);

  const columns = useMemo(
    () =>
      col.columns([
        col.accessor('name', {
          header: '名称',
          enableHiding: false,
          sortFn: 'alphanumeric',
          meta: { label: '名称' },
          cell: (c) => <span className="truncate font-medium">{c.getValue()}</span>,
        }),
        col.accessor('currency', {
          header: '币种',
          enableSorting: false,
          meta: { width: 80 },
          cell: (c) => <span className="font-mono text-12">{c.getValue()}</span>,
        }),
        col.display({
          id: 'version',
          header: '当前版本',
          meta: { width: 96, align: 'right', label: '当前版本' },
          cell: (c) => <span className="tabular-nums">v{c.row.original.current_version.version}</span>,
        }),
        col.display({
          id: 'input',
          header: '输入单价',
          meta: { width: 120, align: 'right', label: '输入单价' },
          cell: (c) => <span className="tabular-nums">{c.row.original.current_version.prices.input}</span>,
        }),
        col.display({
          id: 'output',
          header: '输出单价',
          meta: { width: 120, align: 'right', label: '输出单价' },
          cell: (c) => <span className="tabular-nums">{c.row.original.current_version.prices.output}</span>,
        }),
        col.display({
          id: 'effective',
          header: '生效于',
          meta: { width: 136, label: '生效于' },
          cell: (c) => (
            <RelativeTime value={c.row.original.current_version.effective_from} className="text-ink-2" />
          ),
        }),
        col.display({
          id: 'models',
          header: '模型',
          meta: { width: 80, align: 'right', label: '模型' },
          cell: (c) => <span className="tabular-nums">{usage.get(c.row.original.id) ?? 0}</span>,
        }),
      ]),
    [usage],
  );

  return (
    <DataTable
      label="价格表"
      columns={columns}
      data={priceBooks.data ?? []}
      getRowId={(p) => p.id}
      loading={priceBooks.isPending}
      error={
        priceBooks.error ? (
          <ErrorState
            title="无法加载价格表"
            error={priceBooks.error}
            onRetry={() => void priceBooks.refetch()}
          />
        ) : undefined
      }
      onRowClick={(p) =>
        void navigate({
          to: '/projects/$projectId/models/price-books/$priceBookId',
          params: { projectId, priceBookId: p.id },
        })
      }
      className="min-h-0 flex-1"
      empty={
        <EmptyState
          variant="first-use"
          title="还没有价格表：按每百万 token 写单价，模型选用后用量按它计价"
          action={createButton}
        />
      }
    />
  );
}
