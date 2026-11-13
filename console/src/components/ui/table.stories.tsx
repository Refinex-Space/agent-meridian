import type { Meta, StoryObj } from '@storybook/react-vite';

import { hover, storyFrame } from '@/test/stories';

import { Table, TableBody, TableCaption, TableCell, TableHead, TableHeader, TableRow } from './table';

const ROWS = [
  { id: 'ses_01JC8Z4V7Q', agent: '经营分析助手', user: 'u_8231', tokens: '48,210' },
  { id: 'ses_01JC8W2KX1', agent: '客服质检', user: 'u_1077', tokens: '6,104' },
  { id: 'ses_01JC8T9PA3', agent: '政策解读', user: 'u_5520', tokens: '312' },
];

function Example({
  rows = ROWS,
  selected,
}: {
  rows?: { id: string; agent: string; user: string; tokens: string }[];
  selected?: string;
}) {
  return (
    <Table>
      <TableHeader>
        <TableRow className="hover:bg-transparent">
          <TableHead>会话</TableHead>
          <TableHead>智能体</TableHead>
          <TableHead>终端用户</TableHead>
          <TableHead className="text-right">token</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {rows.length === 0 ? (
          <TableRow className="hover:bg-transparent">
            <TableCell colSpan={4} className="h-24 text-center text-ink-2">
              没有会话
            </TableCell>
          </TableRow>
        ) : (
          rows.map((row) => (
            <TableRow key={row.id} data-state={row.id === selected ? 'selected' : undefined}>
              <TableCell className="font-mono text-12">{row.id}</TableCell>
              <TableCell className="max-w-56 truncate">{row.agent}</TableCell>
              <TableCell>{row.user}</TableCell>
              <TableCell className="text-right">{row.tokens}</TableCell>
            </TableRow>
          ))
        )}
      </TableBody>
    </Table>
  );
}

/** 表格：只有分隔线，行高 40px，表头 12px 石墨色；数字列右对齐。排序、分页、虚拟滚动见 DataTable。 */
const meta = {
  title: '基元/表格',
  component: Table,
  decorators: [storyFrame('w-200')],
} satisfies Meta<typeof Table>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = { name: '默认', render: () => <Example /> };

export const Hover: Story = {
  name: '悬停',
  parameters: hover('tbody tr:nth-child(2)'),
  render: () => <Example />,
};

export const Selected: Story = { name: '选中', render: () => <Example selected="ses_01JC8W2KX1" /> };

export const Empty: Story = { name: '空', render: () => <Example rows={[]} /> };

export const LongContent: Story = {
  name: '长内容',
  render: () => (
    <>
      <Example
        rows={[
          ...ROWS,
          {
            id: 'ses_01JC8Q0B2Z',
            agent: '华东大区经营分析助手（生产环境，只读数据源，按周汇总）',
            user: 'u_9001',
            tokens: '1,204,880',
          },
        ]}
      />
      <Table>
        <TableCaption>超长的智能体名称在单元格内截断，完整名称在详情页。</TableCaption>
      </Table>
    </>
  ),
};
