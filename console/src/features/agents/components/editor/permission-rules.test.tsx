import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { describe, expect, it } from 'vitest';

import { TooltipProvider } from '@/components/ui/tooltip';

import type { PermissionRule } from '../../model/spec';

import { PermissionRules } from './permission-rules';

function Harness({ initial = [] }: { initial?: PermissionRule[] }) {
  const [rules, setRules] = useState<PermissionRule[]>(initial);
  return (
    <TooltipProvider>
      <PermissionRules
        id="rules"
        label="规则"
        rules={rules}
        onChange={setRules}
        toolGroups={[
          { label: '平台工具', tools: ['read_file', 'bash', 'web_fetch', 'web_search', 'knowledge_search'] },
          { label: 'MCP 工具', tools: ['mcp__crm__lookup_order'] },
        ]}
        knowledgeBases={[{ id: 'kb_01', name: '季度经营月报' }]}
        subagents={['reader']}
      />
    </TooltipProvider>
  );
}

const content = () => screen.getByRole('textbox', { name: '规则内容' });
const add = () => screen.getByRole('button', { name: '添加规则' });

describe('PermissionRules', () => {
  it('路径 glob：以 / 开头的内容在本地被拦截，不加入规则', async () => {
    const user = userEvent.setup();
    render(<Harness />);
    await user.type(content(), '/etc/passwd');
    await user.click(add());
    expect(screen.getByText('写相对工作区根的路径，不以 / 开头，例如 reports/**。')).toBeInTheDocument();
    expect(content()).toBeInvalid();
    expect(screen.getByText('还没有规则')).toBeInTheDocument();

    await user.clear(content());
    await user.type(content(), 'reports/**{Enter}');
    const table = screen.getByRole('table', { name: '规则' });
    expect(within(table).getByText('reports/**')).toBeInTheDocument();
    expect(within(table).getByText('放行')).toBeInTheDocument();
  });

  it('留空的内容是整个工具；同样的规则不重复加入', async () => {
    const user = userEvent.setup();
    render(<Harness />);
    await user.click(add());
    expect(within(screen.getByRole('table', { name: '规则' })).getByText('整个工具')).toBeInTheDocument();
    await user.click(add());
    expect(screen.getByText('已有同样的规则。')).toBeInTheDocument();
  });

  it('按所选工具提示正确的写法：web_fetch 要 domain: 前缀，web_search 只有整工具规则', async () => {
    const user = userEvent.setup();
    render(<Harness />);
    await user.click(screen.getByRole('combobox', { name: '规则作用的工具' }));
    await user.click(screen.getByRole('option', { name: 'web_fetch' }));
    expect(content()).toHaveAttribute('placeholder', 'domain:docs.example.com（留空为整个工具）');
    await user.type(content(), 'docs.example.com');
    await user.click(add());
    expect(screen.getByText('写成 domain: 加域名，例如 domain:docs.example.com。')).toBeInTheDocument();

    await user.click(screen.getByRole('combobox', { name: '规则作用的工具' }));
    await user.click(screen.getByRole('option', { name: 'web_search' }));
    expect(content()).toBeDisabled();
  });

  it('knowledge_search 从绑定的知识库中选，删除规则', async () => {
    const user = userEvent.setup();
    render(
      <Harness
        initial={[
          { tool_name: 'bash', rule_content: 'git status:*', behavior: 'ask', layer: 'agent_version' },
        ]}
      />,
    );
    await user.click(screen.getByRole('combobox', { name: '规则作用的工具' }));
    await user.click(screen.getByRole('option', { name: 'knowledge_search' }));
    await user.click(screen.getByRole('combobox', { name: '规则内容' }));
    await user.click(screen.getByRole('option', { name: '季度经营月报' }));
    await user.click(add());
    const table = screen.getByRole('table', { name: '规则' });
    expect(within(table).getByText('季度经营月报')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '删除规则 bash(git status:*)' }));
    expect(within(table).queryByText('git status:*')).toBeNull();
  });
});
