import { render, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { TooltipProvider } from '@/components/ui/tooltip';
import type { TokenUsage as Usage } from '@/lib/api/types';

import { TokenUsage } from './token-usage';

const USAGE: Usage = {
  input_tokens: 48_210,
  output_tokens: 6_104,
  cached_tokens: 31_000,
  cache_creation_tokens: 0,
  reasoning_tokens: 0,
};

describe('TokenUsage', () => {
  it('行内：K、M 缩写，读屏读精确值；缓存写入与推理为 0 时不出现', () => {
    render(
      <TooltipProvider>
        <TokenUsage usage={USAGE} />
      </TooltipProvider>,
    );
    const input = screen.getByText('输入').closest('div') as HTMLElement;
    expect(within(input).getByText('48K')).toHaveAttribute('aria-hidden');
    expect(within(input).getByText('48,210 token')).toHaveClass('sr-only');
    expect(screen.queryByText('推理')).toBeNull();
    expect(screen.queryByText('缓存写入')).toBeNull();
  });

  it('行内：推理有值时出现；带成本时显示币种与两位小数', () => {
    render(
      <TooltipProvider>
        <TokenUsage usage={{ ...USAGE, reasoning_tokens: 1200 }} cost={{ amount: 1.237, currency: 'CNY' }} />
      </TooltipProvider>,
    );
    expect(screen.getByText('推理')).toBeInTheDocument();
    expect(screen.getByText('¥1.24')).toBeInTheDocument();
  });

  it('详情：逐项精确值，含缓存写入与推理', () => {
    render(<TokenUsage usage={USAGE} variant="detail" />);
    expect(screen.getByText('48,210 token')).toBeInTheDocument();
    expect(screen.getByText('缓存写入').nextSibling).toHaveTextContent('0 token');
    expect(screen.getByText('推理')).toBeInTheDocument();
    expect(screen.queryByText('成本')).toBeNull();
  });
});
