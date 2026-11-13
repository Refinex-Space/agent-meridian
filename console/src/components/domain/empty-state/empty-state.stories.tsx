import type { Meta, StoryObj } from '@storybook/react-vite';

import { Button } from '@/components/ui/button';
import { PlusIcon } from '@/components/ui/glyphs';
import { Icon } from '@/components/ui/icon';
import { focus, hover, storyFrame } from '@/test/stories';

import { EmptyState } from './empty-state';

/** 空态：首次使用（一句话加主操作）、筛选为空（清除筛选）、全部处理完；插画 128×96，不写营销文案。 */
const meta = {
  title: '领域组件/EmptyState',
  component: EmptyState,
  decorators: [storyFrame('w-200')],
  args: {
    variant: 'first-use',
    title: '还没有智能体，创建一个开始调试会话',
    action: (
      <Button variant="primary" icon={<Icon glyph={PlusIcon} />}>
        创建智能体
      </Button>
    ),
  },
} satisfies Meta<typeof EmptyState>;

export default meta;
type Story = StoryObj<typeof meta>;

export const FirstUse: Story = { name: '首次使用' };

export const Filtered: Story = {
  name: '筛选为空',
  args: { variant: 'filtered', title: '没有匹配的会话', action: undefined, onClearFilters: () => undefined },
};

export const Clear: Story = {
  name: '全部处理完',
  args: { variant: 'clear', title: '没有等待回应的动作', action: undefined },
};

export const Hover: Story = { name: '悬停', parameters: hover('[data-slot=button]') };

export const Focus: Story = { name: '聚焦', parameters: focus('[data-slot=button]') };

export const Inline: Story = {
  name: '行内',
  decorators: [storyFrame('w-72')],
  args: { variant: 'clear', size: 'inline', title: '没有等待回应的动作', action: undefined },
};

export const LongContent: Story = {
  name: '长内容',
  args: {
    variant: 'filtered',
    title:
      '在"华东大区经营分析助手（生产环境）"最近 30 天的会话中，没有状态为等待处理、终端用户为 u_8231 的会话',
    action: undefined,
    onClearFilters: () => undefined,
  },
};
