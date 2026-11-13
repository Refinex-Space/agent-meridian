import type { Meta, StoryObj } from '@storybook/react-vite';

import { focus, hover, storyFrame } from '@/test/stories';

import { CodeBlock } from './code-block';

const INPUT = JSON.stringify(
  {
    command: 'python export.py --category 下滑品类 --quarter 2026Q3',
    timeout: 600,
    dry_run: false,
    env: { REGION: 'east', LIMIT: null },
  },
  null,
  2,
);

const SCRIPT = `# 导出下滑品类的周明细
python export.py --category "下滑品类" --quarter 2026Q3 \\
  --format csv > /workspace/exports/q3.csv
wc -l /workspace/exports/q3.csv`;

const LOG = Array.from(
  { length: 48 },
  (_, i) => `2026-11-13 10:02:${String(i).padStart(2, '0')} INFO  export: wrote batch ${i + 1}/48 (512 rows)`,
).join('\n');

/** 只读代码与 JSON：surface-inset 底，Shiki 高亮（语法色来自令牌），一键复制，长内容折叠。 */
const meta = {
  title: '领域组件/CodeBlock',
  component: CodeBlock,
  decorators: [storyFrame('w-160')],
  args: { code: INPUT, language: 'json', label: '入参' },
} satisfies Meta<typeof CodeBlock>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = { name: 'JSON' };

export const Shell: Story = { args: { code: SCRIPT, language: 'shell', label: '命令' } };

export const Hover: Story = { name: '悬停', parameters: hover('[data-slot=button]') };

export const Focus: Story = { name: '聚焦', parameters: focus('[data-slot=button]') };

export const LongContent: Story = { name: '长内容', args: { code: LOG, language: 'text', label: '日志' } };

export const NoWrap: Story = {
  name: '不换行',
  args: { code: LOG.split('\n').slice(0, 4).join('  '), language: 'text', label: '日志', wrap: false },
};

export const Empty: Story = { name: '空', args: { code: '', label: '结果' } };

export const Loading: Story = { name: '加载', args: { loading: true } };
