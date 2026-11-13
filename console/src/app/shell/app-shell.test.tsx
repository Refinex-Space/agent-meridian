import { act, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { TooltipProvider } from '@/components/ui/tooltip';
import type { TenantProjects } from '@/lib/access';
import { reportNetworkFailure, reportResponse } from '@/lib/api/connection';
import type { ProjectBudgetStatus } from '@/lib/api/types';
import { renderWithRouter } from '@/test/router';

import { AppShell, type AppShellProps } from './app-shell';
import { BudgetBanner } from './budget-banner';
import { ConnectionBanner } from './connection-banner';

const PROJECTS: TenantProjects[] = [
  {
    tenant_id: 'ten_a',
    tenant_name: '华东大区',
    projects: [
      { id: 'prj_1', name: '华东经营分析', tenant_id: 'ten_a', tenant_name: '华东大区' },
      { id: 'prj_2', name: '门店运营', tenant_id: 'ten_a', tenant_name: '华东大区' },
    ],
  },
];

async function renderShell(props: Partial<AppShellProps> = {}) {
  const handlers = {
    onSwitchProject: vi.fn(),
    onThemeChange: vi.fn(),
    onLogout: vi.fn(),
    onNavigate: vi.fn(),
  };
  const view = await renderWithRouter(
    <TooltipProvider>
      <AppShell
        active="sessions"
        project={{ id: 'prj_1', name: '华东经营分析' }}
        projects={PROJECTS}
        user={{ name: 'Dev Developer', email: 'dev-developer@meridian.local', roles: '项目开发者' }}
        theme="system"
        pendingCount={3}
        breadcrumb={[{ label: '会话' }]}
        {...handlers}
        {...props}
      >
        <p>内容区</p>
      </AppShell>
    </TooltipProvider>,
    { url: '/projects/prj_1/sessions' },
  );
  return { view, ...handlers };
}

describe('应用外壳', () => {
  beforeEach(() => {
    window.localStorage.clear();
  });

  it('十一个一级导航链接到当前项目，当前项标为 aria-current，待处理带计数', async () => {
    await renderShell();
    const nav = screen.getByRole('navigation', { name: '主导航' });
    const links = within(nav).getAllByRole('link');
    expect(links.map((l) => l.textContent)).toEqual([
      '概览',
      '会话',
      'Playground',
      '智能体',
      '环境',
      '能力',
      '知识库',
      '模型',
      '待处理3',
      '用量与预算',
      '设置',
    ]);
    expect(within(nav).getByRole('link', { name: '会话' })).toHaveAttribute('aria-current', 'page');
    expect(within(nav).getByRole('link', { name: /待处理/ })).toHaveAttribute(
      'href',
      '/projects/prj_1/pending',
    );
    expect(screen.getByText('3')).toHaveAccessibleName('3 个待处理动作');
  });

  it('收起导航后记在本地，下次打开仍是收起状态', async () => {
    const user = userEvent.setup();
    const first = await renderShell();
    await user.click(screen.getByRole('button', { name: '收起导航' }));
    expect(window.localStorage.getItem('meridian.nav.collapsed')).toBe('1');
    expect(screen.getByRole('link', { name: '待处理，3 个待处理动作' })).toBeInTheDocument();
    first.view.unmount();
    await renderShell();
    expect(screen.getByRole('button', { name: '展开导航' })).toHaveAttribute('aria-expanded', 'false');
  });

  it('⌘K 打开命令面板，选中页面后跳转并关闭', async () => {
    const user = userEvent.setup();
    const { onNavigate } = await renderShell();
    await user.keyboard('{Meta>}k{/Meta}');
    const dialog = await screen.findByRole('dialog');
    await user.type(within(dialog).getByRole('combobox'), '知识');
    await user.keyboard('{Enter}');
    expect(onNavigate).toHaveBeenCalledWith(expect.objectContaining({ key: 'knowledge' }));
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('项目切换列出租户下能进入的项目；账号菜单显示角色、切换主题与退出', async () => {
    const user = userEvent.setup();
    const { onSwitchProject, onThemeChange, onLogout } = await renderShell();
    await user.click(screen.getByRole('button', { name: '切换项目：华东经营分析' }));
    expect(screen.getByRole('menuitemradio', { name: '华东经营分析' })).toHaveAttribute(
      'aria-checked',
      'true',
    );
    await user.click(screen.getByRole('menuitemradio', { name: '门店运营' }));
    expect(onSwitchProject).toHaveBeenCalledWith('prj_2');

    await user.click(screen.getByRole('button', { name: '账号：Dev Developer' }));
    const menu = screen.getByRole('menu');
    expect(menu).toHaveTextContent('在华东经营分析中是项目开发者');
    expect(within(menu).getByRole('menuitemradio', { name: '跟随系统' })).toHaveAttribute(
      'aria-checked',
      'true',
    );
    await user.click(within(menu).getByRole('menuitemradio', { name: '暗色' }));
    expect(onThemeChange).toHaveBeenCalledWith('dark');
    await user.click(screen.getByRole('button', { name: '账号：Dev Developer' }));
    await user.click(screen.getByRole('menuitem', { name: '退出登录' }));
    expect(onLogout).toHaveBeenCalled();
  });
});

const budget = (patch: Partial<ProjectBudgetStatus>): ProjectBudgetStatus => ({
  project_id: 'prj_1',
  period: '2026-11',
  period_start: '2026-11-01T00:00:00Z',
  period_end: '2026-12-01T00:00:00Z',
  used_tokens: 860_000,
  soft_limit_tokens: 800_000,
  hard_limit_tokens: 1_000_000,
  limit_source: 'project',
  state: 'soft_exceeded',
  ...patch,
});

describe('全局横幅', () => {
  afterEach(() => {
    act(() => reportResponse(200));
  });

  it('预算：越过提醒阈值、用完、没有设置预算各有说明；ok 时不显示', async () => {
    const { rerender } = await renderWithRouter(<BudgetBanner projectId="prj_1" status={budget({})} />);
    expect(screen.getByRole('status')).toHaveTextContent(
      '本月预算已用 86%，超过提醒阈值 80%；用完后新的模型调用会被拒绝。',
    );
    expect(screen.getByRole('link', { name: '查看用量与预算' })).toHaveAttribute(
      'href',
      '/projects/prj_1/usage',
    );
    rerender(
      <BudgetBanner projectId="prj_1" status={budget({ state: 'hard_exceeded', used_tokens: 1_000_000 })} />,
    );
    expect(screen.getByRole('status')).toHaveTextContent('本月预算已用完，新的模型调用会被拒绝。');
    rerender(
      <BudgetBanner
        projectId="prj_1"
        status={budget({
          state: 'hard_exceeded',
          limit_source: 'none',
          hard_limit_tokens: 0,
          soft_limit_tokens: null,
        })}
      />,
    );
    expect(screen.getByRole('status')).toHaveTextContent('本项目还没有设置预算，新的模型调用会被拒绝。');
    rerender(<BudgetBanner projectId="prj_1" status={budget({ hard_limit_tokens: null })} />);
    expect(screen.getByRole('status')).toHaveTextContent('本月已用 860K token，超过提醒阈值 800K。');
    rerender(<BudgetBanner projectId="prj_1" status={budget({ state: 'ok', used_tokens: 1 })} />);
    expect(screen.queryByRole('status')).toBeNull();
  });

  it('预算：关闭后在状态变化时再出现', async () => {
    const user = userEvent.setup();
    const { rerender } = await renderWithRouter(<BudgetBanner projectId="prj_1" status={budget({})} />);
    await user.click(screen.getByRole('button', { name: '关闭预算提醒' }));
    expect(screen.queryByRole('status')).toBeNull();
    rerender(<BudgetBanner projectId="prj_1" status={budget({ used_tokens: 900_000 })} />);
    expect(screen.queryByRole('status')).toBeNull();
    rerender(<BudgetBanner projectId="prj_1" status={budget({ state: 'hard_exceeded' })} />);
    expect(screen.getByRole('status')).toHaveTextContent('本月预算已用完');
  });

  it('与服务端断开：请求失败时出现，恢复后自动消失；关闭后下一次断开再出现', async () => {
    const user = userEvent.setup();
    const onRetry = vi.fn();
    await renderWithRouter(<ConnectionBanner onRetry={onRetry} />);
    expect(screen.queryByRole('status')).toBeNull();
    act(() => reportNetworkFailure());
    expect(screen.getByRole('status')).toHaveTextContent('与服务端的连接已断开');
    await user.click(screen.getByRole('button', { name: '重新连接' }));
    expect(onRetry).toHaveBeenCalled();
    await user.click(screen.getByRole('button', { name: '关闭连接提醒' }));
    expect(screen.queryByRole('status')).toBeNull();
    act(() => reportResponse(200));
    act(() => reportResponse(503));
    expect(screen.getByRole('status')).toBeInTheDocument();
    act(() => reportResponse(200));
    expect(screen.queryByRole('status')).toBeNull();
  });
});
