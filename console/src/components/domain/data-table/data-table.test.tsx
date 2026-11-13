import { act, fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { Button } from '@/components/ui/button';
import { TooltipProvider } from '@/components/ui/tooltip';

import { buildSessionList, SESSION_LIST_NOW, type SessionListRow } from '@mocks/fixtures/session-list';

import { DataTable, type DataTableProps } from './data-table';
import { sessionColumns } from './examples';

const COLUMNS = sessionColumns(SESSION_LIST_NOW);

function renderTable(props: Partial<DataTableProps<SessionListRow>> & { data: SessionListRow[] }) {
  return render(
    <TooltipProvider>
      <DataTable
        label="会话列表"
        columns={COLUMNS}
        getRowId={(row) => row.session.id}
        className="h-96"
        {...props}
      />
    </TooltipProvider>,
  );
}

/** 表体中的数据行（不含虚拟滚动的占位行）。 */
function bodyRows() {
  return screen.getAllByRole('row').filter((r) => r.hasAttribute('data-index'));
}

// jsdom 不做布局：虚拟滚动按 clientHeight 取视口。给 400px 的视口，40px 一行，约 10 行可见。
beforeEach(() => {
  vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get').mockReturnValue(400);
  vi.spyOn(HTMLElement.prototype, 'clientWidth', 'get').mockReturnValue(1000);
});

describe('DataTable', () => {
  it('点击表头排序：数字列先降序（大的在前）再升序，文字列先升序，aria-sort 随之变化', async () => {
    const user = userEvent.setup();
    renderTable({ data: buildSessionList(12) });
    const header = screen.getByRole('columnheader', { name: 'token' });
    expect(header).toHaveAttribute('aria-sort', 'none');
    const tokens = () =>
      bodyRows().map((r) =>
        Number(
          within(r)
            .getByTitle(/token$/)
            .title.replace(/\D/g, ''),
        ),
      );

    await user.click(within(header).getByRole('button'));
    expect(header).toHaveAttribute('aria-sort', 'descending');
    expect(tokens()).toEqual([...tokens()].sort((a, b) => b - a));

    await user.click(within(header).getByRole('button'));
    expect(header).toHaveAttribute('aria-sort', 'ascending');
    expect(tokens()).toEqual([...tokens()].sort((a, b) => a - b));

    const agent = screen.getByRole('columnheader', { name: '智能体' });
    await user.click(within(agent).getByRole('button'));
    expect(agent).toHaveAttribute('aria-sort', 'ascending');
    expect(header).toHaveAttribute('aria-sort', 'none');
  });

  it('客户端分页：每页 50 行，翻页后显示下一段', async () => {
    const user = userEvent.setup();
    renderTable({ data: buildSessionList(120), pagination: { pageSize: 50 } });
    expect(bodyRows()).toHaveLength(50);
    expect(screen.getByText('第 1–50 条，共 120 条')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '上一页' })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: '下一页' }));
    await user.click(screen.getByRole('button', { name: '下一页' }));
    expect(bodyRows()).toHaveLength(20);
    expect(screen.getByText('第 101–120 条，共 120 条')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '下一页' })).toBeDisabled();
  });

  it('列显隐：在菜单中取消勾选后该列消失，不能隐藏的列不出现在菜单里', async () => {
    const user = userEvent.setup();
    renderTable({ data: buildSessionList(3) });
    await user.click(screen.getByRole('button', { name: '显示的列' }));
    expect(screen.queryByRole('menuitemcheckbox', { name: '会话' })).toBeNull();
    await user.click(screen.getByRole('menuitemcheckbox', { name: '终端用户' }));
    expect(screen.queryByRole('columnheader', { name: '终端用户' })).toBeNull();
    expect(screen.getByRole('menuitemcheckbox', { name: '终端用户' })).toHaveAttribute(
      'aria-checked',
      'false',
    );
  });

  it('批量操作：勾选行后工具栏换成批量操作栏，操作拿到选中的行，全选与取消选择', async () => {
    const user = userEvent.setup();
    const onArchive = vi.fn();
    const data = buildSessionList(5);
    renderTable({
      data,
      onRowClick: () => undefined,
      toolbar: <span>工具栏</span>,
      bulkActions: (rows, clear) => (
        <Button
          size="sm"
          onClick={() => {
            onArchive(rows.map((r) => r.session.id));
            clear();
          }}
        >
          归档
        </Button>
      ),
    });
    await user.click(screen.getByRole('checkbox', { name: '选择第 1 行' }));
    await user.click(screen.getByRole('checkbox', { name: '选择第 3 行' }));
    // 点击行内的复选框时焦点留在复选框上，不被行抢走。
    expect(screen.getByRole('checkbox', { name: '选择第 3 行' })).toHaveFocus();
    expect(screen.getByRole('toolbar', { name: '批量操作' })).toHaveTextContent('已选 2 项');
    expect(screen.queryByText('工具栏')).toBeNull();
    expect(screen.getByRole('checkbox', { name: '选择本页全部行' })).toHaveAttribute(
      'data-state',
      'indeterminate',
    );

    await user.click(screen.getByRole('button', { name: '归档' }));
    expect(onArchive).toHaveBeenCalledWith([data[0]?.session.id, data[2]?.session.id]);
    expect(screen.getByText('工具栏')).toBeInTheDocument();

    await user.click(screen.getByRole('checkbox', { name: '选择本页全部行' }));
    expect(screen.getByRole('toolbar', { name: '批量操作' })).toHaveTextContent('已选 5 项');
    await user.click(screen.getByRole('button', { name: '取消选择' }));
    expect(screen.queryByRole('toolbar', { name: '批量操作' })).toBeNull();
  });

  it('超过 200 行启用虚拟滚动：只渲染视口附近的行，滚动后渲染新的一段', () => {
    const data = buildSessionList(10_000);
    const { container } = renderTable({ data });
    const rendered = bodyRows();
    expect(rendered.length).toBeGreaterThan(0);
    expect(rendered.length).toBeLessThan(40);
    expect(rendered[0]).toHaveAttribute('data-index', '0');

    const scroller = container.querySelector<HTMLElement>('[aria-busy], .overflow-auto') as HTMLElement;
    act(() => {
      scroller.scrollTop = 40 * 6000;
      scroller.dispatchEvent(new Event('scroll'));
    });
    const indexes = bodyRows().map((r) => Number(r.getAttribute('data-index')));
    expect(indexes).toContain(6000);
    expect(indexes).not.toContain(0);
    expect(bodyRows().length).toBeLessThan(40);
    // 占位行撑出完整高度，滚动条长度与 1 万行一致。
    const spacers = container.querySelectorAll('tbody > tr[aria-hidden]');
    const height = [...spacers].reduce(
      (sum, tr) => sum + Number.parseFloat((tr as HTMLElement).style.height),
      0,
    );
    expect(height + bodyRows().length * 40).toBe(400_000);
  });

  it('200 行以内不虚拟化，全部渲染', () => {
    renderTable({ data: buildSessionList(200) });
    expect(bodyRows()).toHaveLength(200);
  });

  it('行可点击时方向键在行之间移动，回车打开', () => {
    const onRowClick = vi.fn();
    const data = buildSessionList(5);
    renderTable({ data, onRowClick });
    const [first, second] = bodyRows();
    expect(first).toHaveAttribute('tabIndex', '0');
    expect(second).toHaveAttribute('tabIndex', '-1');
    first?.focus();
    fireEvent.keyDown(first as HTMLElement, { key: 'ArrowDown' });
    expect(bodyRows()[1]).toHaveFocus();
    fireEvent.keyDown(bodyRows()[1] as HTMLElement, { key: 'Enter' });
    expect(onRowClick).toHaveBeenCalledWith(data[1]);
    fireEvent.keyDown(bodyRows()[1] as HTMLElement, { key: 'End' });
    expect(bodyRows()[4]).toHaveFocus();
  });

  it('受控排序：点击表头回调新的排序，由调用方决定顺序（服务端排序）', async () => {
    const user = userEvent.setup();
    const onSortingChange = vi.fn();
    function Controlled() {
      const [sorting, setSorting] = useState([{ id: 'updated_at', desc: true }]);
      return (
        <TooltipProvider>
          <DataTable
            label="会话列表"
            columns={COLUMNS}
            data={buildSessionList(3)}
            getRowId={(row) => row.session.id}
            manualSorting
            sorting={sorting}
            onSortingChange={(next) => {
              onSortingChange(next);
              setSorting(next);
            }}
          />
        </TooltipProvider>
      );
    }
    render(<Controlled />);
    expect(screen.getByRole('columnheader', { name: '更新' })).toHaveAttribute('aria-sort', 'descending');
    await user.click(within(screen.getByRole('columnheader', { name: '智能体' })).getByRole('button'));
    expect(onSortingChange).toHaveBeenCalledWith([{ id: 'agentName', desc: false }]);
  });

  it('空、错误与游标加载更多', async () => {
    const user = userEvent.setup();
    const onLoadMore = vi.fn();
    const { rerender } = renderTable({ data: [], empty: <p>还没有会话</p> });
    expect(screen.getByText('还没有会话')).toBeInTheDocument();

    rerender(
      <TooltipProvider>
        <DataTable
          label="会话列表"
          columns={COLUMNS}
          data={[]}
          getRowId={(row) => row.session.id}
          error={<p role="alert">无法加载会话列表</p>}
        />
      </TooltipProvider>,
    );
    expect(screen.getByRole('alert')).toHaveTextContent('无法加载会话列表');

    rerender(
      <TooltipProvider>
        <DataTable
          label="会话列表"
          columns={COLUMNS}
          data={buildSessionList(100)}
          getRowId={(row) => row.session.id}
          pagination={{ hasMore: true, onLoadMore }}
        />
      </TooltipProvider>,
    );
    expect(screen.getByText('已加载 100 条')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '加载更多' }));
    expect(onLoadMore).toHaveBeenCalledOnce();
  });

  it('有实时更新时提示条数，点击刷新', async () => {
    const user = userEvent.setup();
    const onApply = vi.fn();
    renderTable({ data: buildSessionList(3), updates: { count: 4, onApply } });
    await user.click(screen.getByRole('button', { name: '有 4 条更新，点击刷新' }));
    expect(onApply).toHaveBeenCalledOnce();
  });
});
