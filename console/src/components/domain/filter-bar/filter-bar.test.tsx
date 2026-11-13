import { act, fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { FilterBar } from './filter-bar';
import { parseFilters, serializeFilters, type FilterDef } from './filters';
import { useUrlFilters } from './use-url-filters';

const DEFS: FilterDef[] = [
  {
    key: 'status',
    label: '状态',
    multiple: true,
    options: [
      { value: 'running', label: '运行中' },
      { value: 'requires_action', label: '等待处理' },
      { value: 'idle', label: '空闲' },
    ],
  },
  {
    key: 'created_within',
    label: '创建时间',
    options: [
      { value: '1h', label: '最近 1 小时' },
      { value: '24h', label: '最近 24 小时' },
    ],
  },
];

function Harness() {
  const [values, setValues] = useUrlFilters(DEFS);
  return (
    <FilterBar
      filters={DEFS}
      value={values}
      onChange={setValues}
      search={{ label: '搜索会话', placeholder: '会话 ID 或终端用户' }}
      resultCount={128}
    />
  );
}

const params = () => new URLSearchParams(window.location.search);

describe('filters：URL 参数的读写', () => {
  it('丢弃不在选项中的值，单选只取第一个，多选去重', () => {
    const values = parseFilters(
      '?status=running&status=bogus&status=running&created_within=1h&created_within=24h&q=%20u_82%20',
      DEFS,
    );
    expect(values).toEqual({ q: 'u_82', filters: { status: ['running'], created_within: ['1h'] } });
  });

  it('只改筛选与搜索的键，保留其他参数', () => {
    const search = serializeFilters(
      '?tab=all&status=idle',
      { q: 'ses_01', filters: { status: ['running', 'idle'], created_within: [] } },
      DEFS,
    );
    expect(search).toBe('?tab=all&status=running&status=idle&q=ses_01');
  });
});

describe('FilterBar 与 URL 同步', () => {
  beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    window.history.replaceState(null, '', '/sessions?tab=all&status=running&status=bogus&q=u_82');
  });
  afterEach(() => {
    vi.useRealTimers();
    window.history.replaceState(null, '', '/');
  });

  it('初始取值读自地址栏，无效值被丢弃', () => {
    render(<Harness />);
    expect(screen.getByRole('button', { name: '状态：运行中' })).toBeInTheDocument();
    expect(screen.getByRole('searchbox', { name: '搜索会话' })).toHaveValue('u_82');
    expect(screen.getByRole('status')).toHaveTextContent('128 个结果');
  });

  it('多选：勾选后写回 URL（按选项顺序），菜单保持打开，其他参数保留', async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    render(<Harness />);
    await user.click(screen.getByRole('button', { name: '状态：运行中' }));
    await user.click(screen.getByRole('menuitemcheckbox', { name: '空闲' }));
    expect(params().getAll('status')).toEqual(['running', 'idle']);
    expect(params().get('tab')).toBe('all');
    expect(screen.getByRole('menuitemcheckbox', { name: '空闲' })).toHaveAttribute('aria-checked', 'true');
    await user.click(screen.getByRole('menuitemcheckbox', { name: '运行中' }));
    expect(params().getAll('status')).toEqual(['idle']);
  });

  it('单选：选中写回 URL，选"不限"移除参数', async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    render(<Harness />);
    await user.click(screen.getByRole('button', { name: '创建时间' }));
    await user.click(screen.getByRole('menuitemradio', { name: '最近 24 小时' }));
    expect(params().get('created_within')).toBe('24h');
    expect(screen.getByRole('button', { name: '创建时间：最近 24 小时' })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '创建时间：最近 24 小时' }));
    await user.click(screen.getByRole('menuitemradio', { name: '不限' }));
    expect(params().has('created_within')).toBe(false);
  });

  it('搜索：输入停顿 300ms 后才写回 URL', async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    render(<Harness />);
    const box = screen.getByRole('searchbox', { name: '搜索会话' });
    await user.clear(box);
    await user.type(box, 'ses_01');
    expect(params().get('q')).toBe('u_82');
    await act(() => vi.advanceTimersByTimeAsync(300));
    expect(params().get('q')).toBe('ses_01');
  });

  it('清除筛选：移除筛选与搜索参数，按钮随之消失', async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    render(<Harness />);
    await user.click(screen.getByRole('button', { name: '清除筛选' }));
    expect(window.location.search).toBe('?tab=all');
    expect(screen.getByRole('searchbox', { name: '搜索会话' })).toHaveValue('');
    expect(screen.queryByRole('button', { name: '清除筛选' })).toBeNull();
  });

  it('前进后退时按地址栏重新读取', () => {
    render(<Harness />);
    act(() => {
      window.history.pushState(null, '', '/sessions?status=idle');
      window.dispatchEvent(new PopStateEvent('popstate'));
    });
    expect(screen.getByRole('button', { name: '状态：空闲' })).toBeInTheDocument();
    expect(screen.getByRole('searchbox', { name: '搜索会话' })).toHaveValue('');
  });

  it('按 / 聚焦搜索框；焦点已在输入框里时不拦截', () => {
    render(<Harness />);
    const box = screen.getByRole('searchbox', { name: '搜索会话' });
    fireEvent.keyDown(document.body, { key: '/' });
    expect(box).toHaveFocus();
    const event = new KeyboardEvent('keydown', { key: '/', bubbles: true, cancelable: true });
    box.dispatchEvent(event);
    expect(event.defaultPrevented).toBe(false);
  });
});
