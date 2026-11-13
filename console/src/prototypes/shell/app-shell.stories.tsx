import type { Meta, StoryObj } from '@storybook/react-vite';
import { userEvent, within } from 'storybook/test';

import { EmptyState } from '@/components/domain/empty-state';

import { BUDGET_SOFT_EXCEEDED } from '../fixtures/identity';

import { AppShell } from './app-shell';
import { BudgetBanner } from './budget-banner';

/** 外壳的内容区放一个与页面无关的空态，只看外壳本身。 */
function Content() {
  return (
    <div className="flex h-full items-center justify-center bg-surface">
      <EmptyState variant="clear" title="外壳之内是页面模板，见「原型/页面模板」" size="inline" />
    </div>
  );
}

/**
 * 应用外壳：左侧导航（232px，可收起为 56px 图标栏，状态记在本地）、项目切换、顶栏面包屑、⌘K 命令面板、账号菜单，
 * 以及影响整个应用的横幅（预算告警）。
 */
const meta = {
  title: '原型/应用外壳',
  component: AppShell,
  parameters: { layout: 'fullscreen' },
  args: {
    active: 'agents',
    pendingCount: 4,
    breadcrumb: [{ label: '智能体', href: '/agents' }, { label: '经营分析助手' }],
    defaultCollapsed: false,
    children: <Content />,
  },
} satisfies Meta<typeof AppShell>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Expanded: Story = { name: '展开' };

export const Collapsed: Story = { name: '导航收起', args: { defaultCollapsed: true } };

export const BudgetAlert: Story = {
  name: '预算告警',
  args: { banner: <BudgetBanner alert={BUDGET_SOFT_EXCEEDED} /> },
};

export const BudgetExhausted: Story = {
  name: '预算用完',
  args: { banner: <BudgetBanner alert={{ state: 'hard_exceeded', usedRatio: 1, softRatio: 0.8 }} /> },
};

export const ProjectSwitcher: Story = {
  name: '切换项目',
  play: async ({ canvasElement }) => {
    await userEvent.click(within(canvasElement).getByRole('button', { name: /切换项目/ }));
  },
};

export const CommandPalette: Story = {
  name: '命令面板',
  play: async ({ canvasElement }) => {
    await userEvent.click(within(canvasElement).getByRole('button', { name: /搜索/ }));
  },
};
