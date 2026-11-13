/**
 * 故事与测试共用的示例列：会话列表（与 frontend-design.md「设计定稿·列表页」的线框一致）。
 * 正式的会话列表在 T24 的 features/sessions 中定义自己的列。
 */
import type { Session } from '@/lib/api/types';
import { formatInteger, formatTokens } from '@/lib/format';

import { CopyableId } from '../copyable-id';
import { RelativeTime } from '../relative-time';
import { StatusBadge } from '../status-badge';

import { dataTableColumns } from './features';

/** 示例行：会话加上列表页拼上的显示字段（结构与 mocks/fixtures/session-list 的行相同）。 */
export interface ExampleSessionRow {
  session: Session;
  agentName: string;
  totalTokens: number;
}

const col = dataTableColumns<ExampleSessionRow>();

export function sessionColumns(now?: number) {
  return col.columns([
    col.accessor((row) => row.session.id, {
      id: 'id',
      header: '会话',
      enableHiding: false,
      enableSorting: false,
      meta: { width: 200 },
      cell: (c) => <CopyableId id={c.getValue()} label="会话 ID" />,
    }),
    col.accessor('agentName', {
      header: '智能体',
      sortFn: 'alphanumeric',
      cell: (c) => c.getValue(),
    }),
    col.accessor((row) => row.session.status, {
      id: 'status',
      header: '状态',
      meta: { width: 128 },
      sortFn: 'alphanumeric',
      cell: (c) => <StatusBadge of="session" status={c.getValue()} />,
    }),
    col.accessor((row) => row.session.end_user_id, {
      id: 'end_user',
      header: '终端用户',
      enableSorting: false,
      meta: { width: 120 },
      cell: (c) => c.getValue() ?? <span className="text-ink-2">无</span>,
    }),
    col.accessor('totalTokens', {
      header: 'token',
      sortFn: 'basic',
      meta: { width: 96, align: 'right' },
      cell: (c) => <span title={`${formatInteger(c.getValue())} token`}>{formatTokens(c.getValue())}</span>,
    }),
    col.accessor((row) => row.session.updated_at, {
      id: 'updated_at',
      header: '更新',
      sortFn: 'datetime',
      meta: { width: 120 },
      cell: (c) => <RelativeTime value={c.getValue()} now={now} className="text-ink-2" />,
    }),
  ]);
}
