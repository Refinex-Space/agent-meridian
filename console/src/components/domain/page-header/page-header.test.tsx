import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { Button } from '@/components/ui/button';

import { PageHeader } from './page-header';

describe('PageHeader', () => {
  it('标题是一级标题，面包屑的最后一项是当前页，主操作在标题行', () => {
    render(
      <PageHeader
        title="经营分析助手"
        breadcrumb={[{ label: '智能体', href: '/agents' }, { label: '经营分析助手' }]}
        actions={<Button variant="primary">发布版本</Button>}
      />,
    );
    expect(screen.getByRole('heading', { level: 1, name: '经营分析助手' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '智能体' })).toHaveAttribute('href', '/agents');
    expect(screen.getByRole('navigation', { name: '面包屑' })).toHaveTextContent('智能体/经营分析助手');
    expect(screen.getByText('经营分析助手', { selector: '[aria-current=page]' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '发布版本' })).toBeInTheDocument();
  });

  it('加载中用骨架代替标题，不渲染一级标题', () => {
    const { container } = render(<PageHeader title="经营分析助手" meta="已发布" loading />);
    expect(screen.queryByRole('heading')).toBeNull();
    expect(container.querySelectorAll('[data-slot=skeleton]')).toHaveLength(2);
  });
});
