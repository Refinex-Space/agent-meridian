import type { Meta, StoryObj } from '@storybook/react-vite';
import { useMemo, useState } from 'react';
import { userEvent, within } from 'storybook/test';

import { Button } from '@/components/ui/button';
import { PlusIcon } from '@/components/ui/glyphs';
import { Icon } from '@/components/ui/icon';
import { ApiError } from '@/lib/api/sessions';
import { focus, hover, storyFrame } from '@/test/stories';

import { buildSessionList, SESSION_LIST_NOW, type SessionListRow } from '@mocks/fixtures/session-list';

import { EmptyState } from '../empty-state';
import { ErrorState } from '../error-state';
import { FilterBar, emptyValues, type FilterDef, type FilterValues } from '../filter-bar';

import { DataTable, type DataTableProps } from './data-table';
import { sessionColumns } from './examples';

const COLUMNS = sessionColumns(SESSION_LIST_NOW);
const ROWS = buildSessionList(36);
const BIG = buildSessionList(10_000);

const DEFS: FilterDef[] = [
  {
    key: 'status',
    label: '状态',
    multiple: true,
    options: [
      { value: 'running', label: '运行中' },
      { value: 'requires_action', label: '等待处理' },
      { value: 'idle', label: '空闲' },
      { value: 'terminated', label: '已终止' },
    ],
  },
];

/** 列表页：筛选栏加表格，筛选在客户端完成（正式页面把条件交给接口）。 */
function ListPage({ data, ...props }: Partial<DataTableProps<SessionListRow>> & { data: SessionListRow[] }) {
  const [filters, setFilters] = useState<FilterValues>(emptyValues(DEFS));
  const [active, setActive] = useState<string | null>(null);
  const rows = useMemo(() => {
    const status = filters.filters['status'] ?? [];
    return data.filter(
      (r) =>
        (status.length === 0 || status.includes(r.session.status)) &&
        (filters.q === '' ||
          r.session.id.includes(filters.q) ||
          (r.session.end_user_id ?? '').includes(filters.q)),
    );
  }, [data, filters]);
  return (
    <DataTable
      label="会话列表"
      columns={COLUMNS}
      data={rows}
      getRowId={(row) => row.session.id}
      className="h-[calc(100vh-4rem)]"
      activeRowId={active}
      onRowClick={(row) => setActive(row.session.id)}
      toolbar={
        <FilterBar
          filters={DEFS}
          value={filters}
          onChange={setFilters}
          search={{ label: '搜索会话', placeholder: '会话 ID 或终端用户' }}
          resultCount={rows.length}
        />
      }
      empty={
        <EmptyState
          variant="filtered"
          title="没有匹配的会话"
          onClearFilters={() => setFilters(emptyValues(DEFS))}
        />
      }
      {...props}
    />
  );
}

/**
 * 列表页的表格：排序、分页、列显隐、批量操作、超过 200 行的虚拟滚动、方向键在行间移动；
 * 加载、空、错误占同一块区域。
 */
const meta = {
  title: '领域组件/DataTable',
  component: DataTable<SessionListRow>,
  decorators: [storyFrame('w-full', 'py-4')],
  args: {
    label: '会话列表',
    columns: COLUMNS,
    data: ROWS,
    getRowId: (row: SessionListRow) => row.session.id,
  },
} satisfies Meta<typeof DataTable<SessionListRow>>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
  name: '默认',
  render: () => <ListPage data={ROWS} />,
};

export const Hover: Story = {
  name: '悬停',
  parameters: hover('tbody tr:nth-child(3)'),
  render: () => <ListPage data={ROWS} />,
};

export const Focus: Story = {
  name: '聚焦',
  parameters: focus('tbody tr:nth-child(1)'),
  render: () => <ListPage data={ROWS} />,
};

export const Loading: Story = {
  name: '加载',
  render: () => <ListPage data={[]} loading />,
};

export const FirstUse: Story = {
  name: '空',
  render: () => (
    <ListPage
      data={[]}
      empty={
        <EmptyState
          variant="first-use"
          title="还没有会话，业务系统调用会话 API 后会出现在这里"
          action={
            <Button variant="primary" icon={<Icon glyph={PlusIcon} />}>
              在 Playground 中开始
            </Button>
          }
        />
      }
    />
  ),
};

export const Error: Story = {
  name: '错误',
  render: () => (
    <ListPage
      data={[]}
      error={
        <ErrorState
          title="无法加载会话列表"
          error={
            new ApiError(503, {
              type: 'urn:meridian:error:internal.unavailable',
              title: 'Service Unavailable',
              status: 503,
              detail: '运行时节点正在重启，暂时无法读取会话。',
              instance: '/v1/sessions',
              code: 'internal.unavailable',
              request_id: 'req_01JC9A7M2KX4Q8',
            })
          }
          onRetry={() => undefined}
        />
      }
    />
  ),
};

export const BulkActions: Story = {
  name: '批量操作',
  render: () => (
    <ListPage
      data={ROWS}
      bulkActions={(rows) => (
        <Button size="sm" variant="danger">
          归档 {rows.length} 个会话
        </Button>
      )}
    />
  ),
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await userEvent.click(canvas.getByRole('checkbox', { name: '选择第 2 行' }));
    await userEvent.click(canvas.getByRole('checkbox', { name: '选择第 3 行' }));
  },
};

export const Paged: Story = {
  name: '分页',
  render: () => <ListPage data={buildSessionList(120)} pagination={{ pageSize: 20 }} />,
};

export const Virtualized: Story = {
  name: '虚拟滚动（1 万行）',
  render: () => <ListPage data={BIG} />,
};

export const Updates: Story = {
  name: '实时更新',
  render: () => <ListPage data={ROWS} updates={{ count: 3, onApply: () => undefined }} />,
};

export const LongContent: Story = {
  name: '长内容',
  render: () => (
    <ListPage
      data={ROWS.slice(0, 6).map((row, i) =>
        i === 1
          ? {
              ...row,
              agentName: '华东大区经营分析助手（生产环境，只读数据源，按周汇总的品类明细与政策解读）',
              totalTokens: 1_284_330_112,
            }
          : row,
      )}
    />
  ),
};
