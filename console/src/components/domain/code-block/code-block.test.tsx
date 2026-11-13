import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { mockClipboard } from '@/test/clipboard';

import { CodeBlock } from './code-block';

const JSON_TEXT = JSON.stringify({ command: 'python export.py', timeout: 60, dry_run: false }, null, 2);

describe('CodeBlock', () => {
  it('超过折叠行数时只显示前几行，可展开与收起', () => {
    const code = Array.from({ length: 30 }, (_, i) => `line ${i + 1}`).join('\n');
    render(<CodeBlock code={code} label="日志" collapseAfter={10} />);
    expect(screen.queryByText('line 11')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: '展开全部（共 30 行）' }));
    expect(screen.getByText('line 30')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '收起' }));
    expect(screen.queryByText('line 30')).toBeNull();
  });

  it('复制的是完整内容（包括折叠的行），复制后按钮名称变为"已复制"', async () => {
    const writeText = mockClipboard();
    const code = Array.from({ length: 20 }, (_, i) => `line ${i + 1}`).join('\n');
    render(<CodeBlock code={code} label="日志" collapseAfter={5} />);
    fireEvent.click(screen.getByRole('button', { name: '复制日志' }));
    await act(async () => {
      await Promise.resolve();
    });
    expect(writeText).toHaveBeenCalledWith(code);
    expect(screen.getByRole('button', { name: '已复制日志' })).toBeInTheDocument();
  });

  it('空内容显示"空"，不显示复制按钮', () => {
    render(<CodeBlock code="" label="结果" />);
    expect(screen.getByText('空')).toBeInTheDocument();
    expect(screen.queryByRole('button')).toBeNull();
  });

  it('JSON 的键与值按语法色令牌着色', async () => {
    const { container } = render(<CodeBlock code={JSON_TEXT} language="json" label="入参" />);
    await waitFor(
      () => {
        const key = [...container.querySelectorAll<HTMLElement>('code span span')].find((s) =>
          s.textContent?.includes('command'),
        );
        expect(key?.style.color).toBe('var(--palette-code-key)');
      },
      { timeout: 5000 },
    );
    const number = [...container.querySelectorAll<HTMLElement>('code span span')].find(
      (s) => s.textContent === '60',
    );
    expect(number?.style.color).toBe('var(--palette-code-number)');
  });
});
