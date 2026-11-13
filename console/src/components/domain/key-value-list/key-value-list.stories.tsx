import type { Meta, StoryObj } from '@storybook/react-vite';

import { focus, hover, storyFrame } from '@/test/stories';

import { CopyableId } from '../copyable-id';
import { StatusBadge } from '../status-badge';

import { KeyValueList, type KeyValueItem } from './key-value-list';

const ITEMS: KeyValueItem[] = [
  { label: '状态', value: <StatusBadge of="custom" kind="success" label="已发布 v12" /> },
  { label: 'ID', value: <CopyableId id="agt_01JB7Q2M9XK4" label="智能体 ID" full /> },
  { label: '模型', value: 'qwen-plus', mono: true },
  { label: '环境', value: '生产只读 r3' },
  { label: '档位', value: 'sandbox', mono: true, hint: 'sandbox 档在独立的会话沙箱中执行命令' },
  { label: '子智能体', value: null },
];

/** 详情页的属性列表：标签 160px 石墨色，值墨色；两栏在 1280px 以下合为一栏。 */
const meta = {
  title: '领域组件/KeyValueList',
  component: KeyValueList,
  decorators: [storyFrame('w-200')],
  args: { items: ITEMS },
} satisfies Meta<typeof KeyValueList>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = { name: '默认', decorators: [storyFrame('w-120')] };

export const TwoColumns: Story = { name: '两栏', args: { columns: 2 } };

export const Hover: Story = {
  name: '悬停',
  decorators: [storyFrame('w-120')],
  parameters: hover('dt button'),
};

export const Focus: Story = {
  name: '聚焦',
  decorators: [storyFrame('w-120')],
  parameters: focus('dt button'),
};

export const Loading: Story = { name: '加载', decorators: [storyFrame('w-120')], args: { loading: true } };

export const Empty: Story = {
  name: '空',
  decorators: [storyFrame('w-120')],
  args: {
    items: [
      { label: '描述', value: '' },
      { label: '通知地址', value: null },
    ],
  },
};

export const LongContent: Story = {
  name: '长内容',
  decorators: [storyFrame('w-120')],
  args: {
    items: [
      {
        label: '系统提示词摘要',
        value:
          '你是华东大区的经营分析助手。回答前先检索知识库中的政策文件，引用时写明文件名与条款；涉及金额时保留两位小数并标明币种。',
      },
      {
        label: '工作区路径',
        value: '/workspace/tenants/t_01JB/projects/p_01JC/sessions/ses_01JC8Z4V7QX3M9N2P8R5/exports',
        mono: true,
      },
    ],
  },
};
