import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { TooltipProvider } from '@/components/ui/tooltip';

import { KeyValueList } from './key-value-list';

describe('KeyValueList', () => {
  it('标签与值成对出现，空值显示"未设置"', () => {
    render(
      <TooltipProvider>
        <KeyValueList
          items={[
            { label: '模型', value: 'qwen-plus', mono: true },
            { label: '环境', value: null },
          ]}
        />
      </TooltipProvider>,
    );
    expect(screen.getByText('模型').nextSibling).toHaveTextContent('qwen-plus');
    expect(screen.getByText('环境').nextSibling).toHaveTextContent('未设置');
  });

  it('补充说明放在 info 按钮的提示里，按钮有可访问名称', () => {
    render(
      <TooltipProvider>
        <KeyValueList items={[{ label: '档位', value: 'sandbox', hint: '在独立的沙箱中执行命令' }]} />
      </TooltipProvider>,
    );
    expect(screen.getByRole('button', { name: '档位的说明' })).toBeInTheDocument();
  });

  it('加载中按条数画骨架并标记 aria-busy', () => {
    const { container } = render(
      <KeyValueList
        loading
        items={[
          { label: '模型', value: '' },
          { label: '环境', value: '' },
        ]}
      />,
    );
    expect(container.firstChild).toHaveAttribute('aria-busy', 'true');
    expect(container.querySelectorAll('[data-slot=skeleton]')).toHaveLength(4);
  });
});
