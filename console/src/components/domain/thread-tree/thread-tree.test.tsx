import { fireEvent, render, screen, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import { buildQ3Scenario, Q3_FIXED_START, THREAD_ANALYST, THREAD_POLICY } from '@mocks/fixtures/q3-session';

import { buildTimeline } from '../session-timeline';

import { MAIN_THREAD_KEY, threadTreeItems } from './from-timeline';
import { ThreadTree, type ThreadTreeItem } from './thread-tree';

const ITEMS: ThreadTreeItem[] = [
  { id: 'main', parentId: null, label: '主线程', status: 'running' },
  { id: 'thr_a', parentId: 'main', label: 'data-analyst', status: 'running' },
  { id: 'thr_b', parentId: 'main', label: 'policy-reader', status: 'failure', note: '预算耗尽' },
  { id: 'thr_c', parentId: 'thr_a', label: 'sql-writer', status: 'idle' },
];

function item(name: string) {
  return screen.getByRole('treeitem', { name: new RegExp(`^${name}`) });
}

describe('ThreadTree', () => {
  it('按父子关系渲染层级，选中项 aria-selected，异常结束写出原因', () => {
    render(<ThreadTree items={ITEMS} selectedId="thr_a" onSelect={vi.fn()} />);
    const tree = screen.getByRole('tree', { name: '线程' });
    expect(within(tree).getAllByRole('treeitem')).toHaveLength(4);
    expect(item('主线程')).toHaveAttribute('aria-level', '1');
    expect(item('sql-writer')).toHaveAttribute('aria-level', '3');
    expect(item('data-analyst')).toHaveAttribute('aria-selected', 'true');
    expect(item('policy-reader')).toHaveTextContent('预算耗尽');
  });

  it('键盘：上下移动焦点，左键收起、右键展开，回车选择', () => {
    const onSelect = vi.fn();
    render(<ThreadTree items={ITEMS} selectedId={null} onSelect={onSelect} />);
    const main = item('主线程');
    expect(main).toHaveAttribute('tabIndex', '0');
    main.focus();
    fireEvent.keyDown(main, { key: 'ArrowDown' });
    const analyst = item('data-analyst');
    expect(analyst).toHaveFocus();

    fireEvent.keyDown(analyst, { key: 'ArrowLeft' });
    expect(analyst).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByRole('treeitem', { name: /^sql-writer/ })).toBeNull();
    fireEvent.keyDown(analyst, { key: 'ArrowRight' });
    expect(analyst).toHaveAttribute('aria-expanded', 'true');
    fireEvent.keyDown(analyst, { key: 'ArrowRight' });
    expect(item('sql-writer')).toHaveFocus();

    fireEvent.keyDown(item('sql-writer'), { key: 'ArrowLeft' });
    expect(analyst).toHaveFocus();
    fireEvent.keyDown(analyst, { key: 'End' });
    expect(item('policy-reader')).toHaveFocus();
    fireEvent.keyDown(item('policy-reader'), { key: 'Enter' });
    expect(onSelect).toHaveBeenCalledWith('thr_b');
  });

  it('点击选择', () => {
    const onSelect = vi.fn();
    render(<ThreadTree items={ITEMS} selectedId={null} onSelect={onSelect} />);
    fireEvent.click(item('sql-writer'));
    expect(onSelect).toHaveBeenCalledWith('thr_c');
  });
});

describe('threadTreeItems', () => {
  it('由时间轴模型得到主线程与两个子线程，主线程的状态取会话状态', () => {
    const scenario = buildQ3Scenario(Q3_FIXED_START);
    const model = buildTimeline(scenario.turnOne);
    const items = threadTreeItems(model, 'idle');
    expect(items[0]).toEqual({ id: MAIN_THREAD_KEY, parentId: null, label: '主线程', status: 'idle' });
    expect(items.map((i) => i.id)).toEqual([MAIN_THREAD_KEY, THREAD_ANALYST, THREAD_POLICY]);
    expect(items.slice(1).every((i) => i.parentId === MAIN_THREAD_KEY && i.status === 'idle')).toBe(true);
  });
});
