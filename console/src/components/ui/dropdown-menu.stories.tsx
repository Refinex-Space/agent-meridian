import type { Meta, StoryObj } from '@storybook/react-vite';
import { userEvent } from 'storybook/test';

import { storyFrame } from '@/test/stories';

import { Button } from './button';
import {
  DropdownMenu,
  DropdownMenuCheckboxItem,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuSeparator,
  DropdownMenuShortcut,
  DropdownMenuTrigger,
} from './dropdown-menu';
import { CopyIcon, DotsThreeIcon, PencilSimpleIcon, TrashIcon } from './glyphs';
import { Icon } from './icon';

function RowMenu({ disabled = false }: { disabled?: boolean }) {
  return (
    <DropdownMenu defaultOpen>
      <DropdownMenuTrigger asChild>
        <Button variant="ghost" size="icon-sm" aria-label="更多操作">
          <Icon glyph={DotsThreeIcon} />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent>
        <DropdownMenuItem>
          <Icon glyph={PencilSimpleIcon} />
          编辑
          <DropdownMenuShortcut>E</DropdownMenuShortcut>
        </DropdownMenuItem>
        <DropdownMenuItem disabled={disabled}>
          <Icon glyph={CopyIcon} />
          复制为新智能体
        </DropdownMenuItem>
        <DropdownMenuSeparator />
        <DropdownMenuItem variant="danger" disabled={disabled}>
          <Icon glyph={TrashIcon} />
          删除智能体
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

/** 下拉菜单：针对当前对象的一组操作；删除类为信号红文字。 */
const meta = {
  title: '基元/下拉菜单',
  component: DropdownMenu,
  decorators: [storyFrame()],
} satisfies Meta<typeof DropdownMenu>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = { name: '默认', render: () => <RowMenu /> };

export const Focus: Story = {
  name: '聚焦',
  render: () => <RowMenu />,
  // 菜单打开后焦点在菜单里，方向键高亮第一项（菜单项的高亮就是它的焦点状态）。
  play: async () => {
    await userEvent.keyboard('{ArrowDown}');
  },
};

export const Disabled: Story = { name: '禁用', render: () => <RowMenu disabled /> };

export const Selection: Story = {
  name: '勾选与单选',
  render: () => (
    <DropdownMenu defaultOpen>
      <DropdownMenuTrigger asChild>
        <Button>显示的列</Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent className="w-52">
        <DropdownMenuLabel>列</DropdownMenuLabel>
        <DropdownMenuCheckboxItem checked>智能体</DropdownMenuCheckboxItem>
        <DropdownMenuCheckboxItem checked>状态</DropdownMenuCheckboxItem>
        <DropdownMenuCheckboxItem checked={false}>终端用户</DropdownMenuCheckboxItem>
        <DropdownMenuSeparator />
        <DropdownMenuLabel>密度</DropdownMenuLabel>
        <DropdownMenuRadioGroup value="regular">
          <DropdownMenuRadioItem value="regular">标准</DropdownMenuRadioItem>
          <DropdownMenuRadioItem value="compact">紧凑</DropdownMenuRadioItem>
        </DropdownMenuRadioGroup>
      </DropdownMenuContent>
    </DropdownMenu>
  ),
};

export const LongContent: Story = {
  name: '长内容',
  render: () => (
    <DropdownMenu defaultOpen>
      <DropdownMenuTrigger asChild>
        <Button>切换项目</Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent className="max-w-72">
        {['华东经营分析', '华南客服质检（含历史工单回放与质检规则调优的长期项目）', '总部财务'].map(
          (name) => (
            <DropdownMenuItem key={name}>
              <span className="truncate">{name}</span>
            </DropdownMenuItem>
          ),
        )}
      </DropdownMenuContent>
    </DropdownMenu>
  ),
};
