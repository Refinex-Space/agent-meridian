import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import { TooltipProvider } from '@/components/ui/tooltip';

import { InspectorPanel, InspectorSection } from './inspector-panel';

const RAW = { id: 'evt_1', type: 'agent.tool_use', data: { name: 'bash' } };

function renderPanel(props: Parameters<typeof InspectorPanel>[0]) {
  return render(
    <TooltipProvider>
      <InspectorPanel {...props} />
    </TooltipProvider>,
  );
}

describe('InspectorPanel', () => {
  it('没有选中时显示一句提示，没有标签页', () => {
    renderPanel({});
    expect(screen.getByRole('complementary', { name: '检查器' })).toHaveTextContent('选择一个事件');
    expect(screen.queryByRole('tab')).toBeNull();
  });

  it('选中时有概要与原始 JSON 两页，原始 JSON 可复制', async () => {
    const user = userEvent.setup();
    renderPanel({
      title: '调用 bash',
      raw: RAW,
      children: <InspectorSection title="入参">python export.py</InspectorSection>,
    });
    expect(screen.getByRole('heading', { level: 2, name: '调用 bash' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 3, name: '入参' })).toBeInTheDocument();
    await user.click(screen.getByRole('tab', { name: '原始 JSON' }));
    expect(screen.getByRole('tabpanel')).toHaveTextContent('"type": "agent.tool_use"');
    expect(screen.getByRole('button', { name: '复制原始 JSON' })).toBeInTheDocument();
  });

  it('收起按钮回调；加载中标记 aria-busy', async () => {
    const user = userEvent.setup();
    const onCollapse = vi.fn();
    renderPanel({ title: '调用 bash', loading: true, onCollapse });
    expect(screen.getByRole('complementary')).toHaveAttribute('aria-busy', 'true');
    await user.click(screen.getByRole('button', { name: '收起检查器' }));
    expect(onCollapse).toHaveBeenCalledOnce();
  });
});
