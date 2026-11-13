import type { Meta, StoryObj } from '@storybook/react-vite';

import { storyFrame } from '@/test/stories';

import {
  Command,
  CommandDialog,
  CommandEmpty,
  CommandGroup,
  CommandInput,
  CommandItem,
  CommandList,
  CommandSeparator,
  CommandShortcut,
} from './command';
import { AgentGlyph, GaugeIcon, GearSixIcon, KnowledgeGlyph, ThreadGlyph } from './glyphs';
import { Icon } from './icon';

function Items() {
  return (
    <>
      <CommandEmpty>没有匹配的页面或对象</CommandEmpty>
      <CommandGroup heading="页面">
        <CommandItem>
          <Icon glyph={GaugeIcon} />
          概览
        </CommandItem>
        <CommandItem>
          <Icon glyph={ThreadGlyph} />
          会话
          <CommandShortcut>G S</CommandShortcut>
        </CommandItem>
        <CommandItem disabled>
          <Icon glyph={GearSixIcon} />
          设置
        </CommandItem>
      </CommandGroup>
      <CommandSeparator />
      <CommandGroup heading="智能体">
        <CommandItem>
          <Icon glyph={AgentGlyph} />
          经营分析助手
        </CommandItem>
        <CommandItem>
          <Icon glyph={KnowledgeGlyph} />
          华东政策文件库
          <CommandShortcut>知识库</CommandShortcut>
        </CommandItem>
      </CommandGroup>
    </>
  );
}

/** 命令面板：⌘K 打开，贴近顶部，方向键选择、回车跳转。 */
const meta = {
  title: '基元/命令面板',
  component: Command,
  decorators: [storyFrame()],
} satisfies Meta<typeof Command>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
  name: '默认',
  render: () => (
    <CommandDialog defaultOpen>
      <CommandInput placeholder="搜索页面、智能体、会话 ID" />
      <CommandList>
        <Items />
      </CommandList>
    </CommandDialog>
  ),
};

export const Empty: Story = {
  name: '空',
  render: () => (
    <CommandDialog defaultOpen>
      <CommandInput placeholder="搜索页面、智能体、会话 ID" value="ses_404" />
      <CommandList>
        <Items />
      </CommandList>
    </CommandDialog>
  ),
};

export const LongContent: Story = {
  name: '长内容',
  render: () => (
    <Command className="w-140 rounded-overlay border border-rule">
      <CommandInput placeholder="搜索会话" />
      <CommandList>
        <CommandGroup heading="会话">
          {Array.from({ length: 24 }, (_, i) => (
            <CommandItem key={i} value={`ses_${i}`}>
              <Icon glyph={ThreadGlyph} />
              <span className="font-mono text-12">ses_01JC8Z{String(i).padStart(4, '0')}</span>
              <CommandShortcut>经营分析助手</CommandShortcut>
            </CommandItem>
          ))}
        </CommandGroup>
      </CommandList>
    </Command>
  ),
};
