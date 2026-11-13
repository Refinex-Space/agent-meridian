import type { Meta, StoryObj } from '@storybook/react-vite';

import { storyFrame } from '@/test/stories';

import { Button } from './button';
import { InfoIcon } from './glyphs';
import { Icon } from './icon';
import { Tooltip, TooltipContent, TooltipTrigger } from './tooltip';

/** 提示：补充信息放在 info 图标的提示里，不写成区块下的小字。悬停或聚焦 300ms 后出现。 */
const meta = {
  title: '基元/提示',
  component: Tooltip,
  decorators: [storyFrame(undefined, 'pt-24')],
} satisfies Meta<typeof Tooltip>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
  name: '默认',
  render: () => (
    <Tooltip defaultOpen>
      <TooltipTrigger asChild>
        <Button variant="ghost" size="icon-sm" aria-label="档位说明">
          <Icon glyph={InfoIcon} />
        </Button>
      </TooltipTrigger>
      <TooltipContent side="right">sandbox 档在独立的沙箱中执行命令</TooltipContent>
    </Tooltip>
  ),
};

export const LongContent: Story = {
  name: '长内容',
  render: () => (
    <Tooltip defaultOpen>
      <TooltipTrigger asChild>
        <Button variant="ghost" size="icon-sm" aria-label="预算说明">
          <Icon glyph={InfoIcon} />
        </Button>
      </TooltipTrigger>
      <TooltipContent side="right">
        四级预算按平台、租户、项目、智能体逐级生效，任一级耗尽都会拒绝新的模型调用；限额每小时与账本对账一次。
      </TooltipContent>
    </Tooltip>
  ),
};
