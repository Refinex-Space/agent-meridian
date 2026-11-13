import type { Meta, StoryObj } from '@storybook/react-vite';

import { Button } from '@/components/ui/button';
import { PlusIcon } from '@/components/ui/glyphs';
import { Icon } from '@/components/ui/icon';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { focus, hover, storyFrame } from '@/test/stories';

import { CopyableId } from '../copyable-id';
import { RelativeTime } from '../relative-time';
import { StatusBadge } from '../status-badge';

import { PageHeader } from './page-header';

const DETAIL_META = (
  <>
    <StatusBadge of="custom" kind="success" label="已发布 v12" />
    <CopyableId id="agt_01JB7Q2M9XK4" label="智能体 ID" />
    <span>
      更新于 <RelativeTime value="2026-11-12T16:40:00+08:00" now={Date.parse('2026-11-13T10:00:00+08:00')} />
    </span>
  </>
);

/** 页面标题区：面包屑、标题、主操作；详情页加一行元信息与标签页。 */
const meta = {
  title: '领域组件/PageHeader',
  component: PageHeader,
  decorators: [storyFrame('w-240', 'p-0 px-8 bg-canvas')],
  args: {
    title: '会话',
    actions: (
      <Button variant="primary" icon={<Icon glyph={PlusIcon} />}>
        创建会话
      </Button>
    ),
  },
} satisfies Meta<typeof PageHeader>;

export default meta;
type Story = StoryObj<typeof meta>;

export const List: Story = { name: '列表页' };

const TABS = [
  ['summary', '概要'],
  ['versions', '版本'],
  ['sessions', '会话'],
  ['permissions', '权限'],
] as const;

export const Detail: Story = {
  name: '详情页',
  args: {
    title: '经营分析助手',
    breadcrumb: [{ label: '智能体', href: '#agents' }, { label: '经营分析助手' }],
    meta: DETAIL_META,
    actions: (
      <>
        <Button>编辑</Button>
        <Button variant="primary">发布版本</Button>
      </>
    ),
  },
  // 标签页的触发器在页头里、内容在页头下方，两者都放在同一个 Tabs 里。
  render: (args) => (
    <Tabs defaultValue="summary">
      <PageHeader {...args}>
        <TabsList>
          {TABS.map(([value, label]) => (
            <TabsTrigger key={value} value={value}>
              {label}
            </TabsTrigger>
          ))}
        </TabsList>
      </PageHeader>
      {TABS.map(([value, label]) => (
        <TabsContent key={value} value={value} className="py-6 text-13 text-ink-2">
          {label}的内容
        </TabsContent>
      ))}
    </Tabs>
  ),
};

export const Hover: Story = { name: '悬停', parameters: hover('[data-slot=button]') };

export const Focus: Story = { name: '聚焦', parameters: focus('[data-slot=button]') };

export const Loading: Story = { name: '加载', args: { loading: true, meta: DETAIL_META } };

export const LongContent: Story = {
  name: '长内容',
  args: {
    title: '华东大区经营分析助手（生产环境，只读数据源，按周汇总的品类明细与政策解读）',
    breadcrumb: [{ label: '智能体', href: '#agents' }, { label: '华东大区经营分析助手（生产环境）' }],
    meta: DETAIL_META,
  },
};
