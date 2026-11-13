import { useQuery } from '@tanstack/react-query';
import { useNavigate } from '@tanstack/react-router';
import { useMemo, type ReactNode } from 'react';

import { DataTable, dataTableColumns } from '@/components/domain/data-table';
import { EmptyState } from '@/components/domain/empty-state';
import { ErrorState } from '@/components/domain/error-state';
import { FilterBar, emptyValues, useSearchFilters, type FilterDef } from '@/components/domain/filter-bar';
import { RelativeTime } from '@/components/domain/relative-time';
import { connectivityStatus, StatusBadge } from '@/components/domain/status-badge';
import { useProject } from '@/lib/access';
import type { McpServer } from '@/lib/api/types';

import { mcpServersQuery } from '../api/queries';
import { TRANSPORT_LABEL } from '../model/mcp';

const NO_FILTERS: readonly FilterDef[] = [];
const col = dataTableColumns<McpServer>();

export interface McpTableProps {
  /** 首次使用的空态里的主操作（"登记 MCP 服务"）。 */
  registerButton: ReactNode;
}

/** MCP 服务列表：服务名、地址、传输、工具数、连接状态与最近探测时间；点行进入服务详情。 */
export function McpTable({ registerButton }: McpTableProps) {
  const { tenantId, projectId } = useProject();
  const navigate = useNavigate();
  const servers = useQuery(mcpServersQuery({ tenantId, projectId }));
  const [filters, setFilters] = useSearchFilters(NO_FILTERS);

  const q = filters.q.trim().toLowerCase();
  const rows = (servers.data ?? []).filter(
    (s) => !q || s.name.includes(q) || s.url.toLowerCase().includes(q),
  );

  const columns = useMemo(
    () =>
      col.columns([
        col.accessor('name', {
          header: '服务名',
          enableHiding: false,
          sortFn: 'alphanumeric',
          meta: { width: 200 },
          cell: (c) => <span className="truncate font-mono text-12 font-medium">{c.getValue()}</span>,
        }),
        col.accessor('url', {
          header: '地址',
          enableSorting: false,
          meta: { label: '地址' },
          cell: (c) => <span className="truncate font-mono text-12 text-ink-2">{c.getValue()}</span>,
        }),
        col.accessor('transport', {
          header: '传输',
          enableSorting: false,
          meta: { width: 144 },
          cell: (c) => TRANSPORT_LABEL[c.getValue()],
        }),
        col.display({
          id: 'tools',
          header: '工具',
          meta: { width: 80, align: 'right', label: '工具' },
          cell: (c) => <span className="tabular-nums">{c.row.original.tools.length}</span>,
        }),
        col.display({
          id: 'status',
          header: '连接状态',
          meta: { width: 120, label: '连接状态' },
          cell: (c) => {
            const { kind, label } = connectivityStatus(c.row.original.last_check);
            return <StatusBadge of="custom" kind={kind} label={label} />;
          },
        }),
        col.display({
          id: 'checked',
          header: '最近探测',
          meta: { width: 136, label: '最近探测' },
          cell: (c) => {
            const at = c.row.original.last_check?.checked_at ?? c.row.original.probed_at;
            return <RelativeTime value={at} className="text-ink-2" />;
          },
        }),
      ]),
    [],
  );

  return (
    <DataTable
      label="MCP 服务列表"
      columns={columns}
      data={rows}
      getRowId={(s) => s.id}
      loading={servers.isPending}
      error={
        servers.error ? (
          <ErrorState
            title="无法加载 MCP 服务"
            error={servers.error}
            onRetry={() => void servers.refetch()}
          />
        ) : undefined
      }
      onRowClick={(s) =>
        void navigate({
          to: '/projects/$projectId/capabilities/mcp-servers/$mcpServerId',
          params: { projectId, mcpServerId: s.id },
        })
      }
      className="min-h-0 flex-1"
      empty={
        q ? (
          <EmptyState
            variant="filtered"
            title="没有匹配的 MCP 服务"
            onClearFilters={() => setFilters(emptyValues(NO_FILTERS))}
          />
        ) : (
          <EmptyState
            variant="first-use"
            title="还没有 MCP 服务：登记后它的工具可以挂到智能体上"
            action={registerButton}
          />
        )
      }
      toolbar={
        <FilterBar
          filters={NO_FILTERS}
          value={filters}
          onChange={setFilters}
          search={{ label: '搜索 MCP 服务', placeholder: '服务名或地址' }}
          resultCount={q ? rows.length : undefined}
        />
      }
    />
  );
}
