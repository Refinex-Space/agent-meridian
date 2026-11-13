import { functionalUpdate, useTable, type RowData, type SortingState } from '@tanstack/react-table';
import { useVirtualizer } from '@tanstack/react-virtual';
import { useEffect, useRef, useState, type KeyboardEvent, type ReactNode } from 'react';

import { Button } from '@/components/ui/button';
import { Checkbox } from '@/components/ui/checkbox';
import {
  DropdownMenu,
  DropdownMenuCheckboxItem,
  DropdownMenuContent,
  DropdownMenuLabel,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import {
  ArrowDownIcon,
  ArrowUpIcon,
  CaretLeftIcon,
  CaretRightIcon,
  ColumnsIcon,
} from '@/components/ui/glyphs';
import { Icon } from '@/components/ui/icon';
import { Skeleton } from '@/components/ui/skeleton';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { cn } from '@/lib/cn';
import { formatInteger } from '@/lib/format';
import { useDelayedVisible } from '@/lib/use-delayed-visible';

import { dataTableFeatures, type DataTableColumns } from './features';

/** 超过这么多行启用虚拟滚动（frontend-design.md「性能预算」）。 */
export const VIRTUALIZE_THRESHOLD = 200;
const ROW_HEIGHT = 40;
const SELECT_COLUMN_WIDTH = 44;
const SKELETON_ROWS = 8;

/** 客户端分页：数据全部在内存里，按页显示。 */
export interface PagePagination {
  pageSize: number;
}

/** 游标分页：已加载的行全部显示（超过 200 行时虚拟滚动），底部"加载更多"取下一页。 */
export interface CursorPagination {
  hasMore: boolean;
  loadingMore?: boolean;
  onLoadMore: () => void;
}

export interface DataTableProps<TData extends RowData> {
  /** 表格的可访问名称，例如"会话列表"。 */
  label: string;
  columns: DataTableColumns<TData>;
  data: readonly TData[];
  getRowId: (row: TData) => string;
  /** 表格上方的工具栏左侧（通常是 FilterBar）；右侧固定是列显隐。 */
  toolbar?: ReactNode;
  /** 加载中：150ms 后显示与最终行高、列宽一致的骨架。 */
  loading?: boolean;
  /** 区域加载失败时的内容（ErrorState），代替表体。 */
  error?: ReactNode;
  /** 没有数据时的内容（EmptyState）。 */
  empty?: ReactNode;
  /** 受控排序（服务端排序时与 manualSorting 一起用）；不传时在客户端排序。 */
  sorting?: SortingState;
  onSortingChange?: (sorting: SortingState) => void;
  manualSorting?: boolean;
  pagination?: PagePagination | CursorPagination;
  /** 批量操作：给出时第一列是选择框，选中后工具栏换成批量操作栏。`clear` 清空选择。 */
  bulkActions?: (rows: TData[], clear: () => void) => ReactNode;
  /** 点击行或在行上按回车；给出时行可聚焦，方向键在行之间移动。 */
  onRowClick?: (row: TData) => void;
  /** 当前打开的行（例如抽屉里正在看的对象），海图蓝浅底。 */
  activeRowId?: string | null;
  /** 列表的实时变化不打断用户：表头上方提示"有 N 条更新"，点击后刷新。 */
  updates?: { count: number; onApply: () => void };
  className?: string;
}

function isCursor(p: PagePagination | CursorPagination | undefined): p is CursorPagination {
  return p !== undefined && 'onLoadMore' in p;
}

/**
 * 列表页的表格：排序、分页（客户端分页或游标加载更多）、列显隐、批量操作、超过 200 行的虚拟滚动、
 * 行的键盘导航；加载、空、错误三种状态占同一块区域，几何不跳动。只有分隔线，没有斑马纹与卡片。
 */
export function DataTable<TData extends RowData>({
  label,
  columns,
  data,
  getRowId,
  toolbar,
  loading = false,
  error,
  empty,
  sorting,
  onSortingChange,
  manualSorting = false,
  pagination,
  bulkActions,
  onRowClick,
  activeRowId,
  updates,
  className,
}: DataTableProps<TData>) {
  const pageSize = pagination && !isCursor(pagination) ? pagination.pageSize : null;
  const selectable = bulkActions !== undefined;

  const table = useTable({
    features: dataTableFeatures,
    columns,
    data,
    getRowId: (row) => getRowId(row),
    enableRowSelection: selectable,
    manualSorting,
    manualPagination: pageSize === null,
    initialState: { pagination: { pageIndex: 0, pageSize: pageSize ?? Number.MAX_SAFE_INTEGER } },
    ...(sorting !== undefined && {
      state: { sorting },
      onSortingChange: (updater) => onSortingChange?.(functionalUpdate(updater, sorting)),
    }),
  });

  const rows = table.getRowModel().rows;
  const leafColumns = table.getVisibleLeafColumns();
  const columnCount = leafColumns.length + (selectable ? 1 : 0);
  const selectedRows = table.getSelectedRowModel().rows;
  const virtualize = rows.length > VIRTUALIZE_THRESHOLD;
  const showSkeleton = useDelayedVisible(loading);

  const scrollRef = useRef<HTMLDivElement>(null);
  // 本项目不启用 React Compiler；这条规则只提示编译器会跳过本组件。
  // eslint-disable-next-line react-hooks/incompatible-library
  const virtualizer = useVirtualizer({
    count: virtualize ? rows.length : 0,
    getScrollElement: () => scrollRef.current,
    estimateSize: () => ROW_HEIGHT,
    getItemKey: (index) => rows[index]?.id ?? index,
    overscan: 12,
  });
  const items = virtualizer.getVirtualItems();
  const padTop = virtualize ? (items[0]?.start ?? 0) : 0;
  const padBottom = virtualize ? virtualizer.getTotalSize() - (items.at(-1)?.end ?? 0) : 0;
  const visibleIndexes = virtualize ? items.map((item) => item.index) : rows.map((_, i) => i);

  // 行的键盘导航：焦点所在行的下标；虚拟滚动时先滚到那一行，渲染后再聚焦。
  const [focusIndex, setFocusIndex] = useState<number | null>(null);
  // 只有方向键移动时才把焦点交给行；点击行内的复选框、按钮时焦点留在原处。
  const pendingFocus = useRef<number | null>(null);
  useEffect(() => {
    if (pendingFocus.current === null) return;
    const el = scrollRef.current?.querySelector<HTMLElement>(`tr[data-index="${pendingFocus.current}"]`);
    if (!el) return;
    pendingFocus.current = null;
    el.focus();
  });
  const moveFocus = (index: number) => {
    const next = Math.max(0, Math.min(rows.length - 1, index));
    pendingFocus.current = next;
    setFocusIndex(next);
    if (virtualize) virtualizer.scrollToIndex(next, { align: 'auto' });
  };
  const onRowKeyDown = (e: KeyboardEvent<HTMLTableRowElement>, index: number) => {
    if (e.target !== e.currentTarget) return;
    if (e.key === 'ArrowDown') moveFocus(index + 1);
    else if (e.key === 'ArrowUp') moveFocus(index - 1);
    else if (e.key === 'Home') moveFocus(0);
    else if (e.key === 'End') moveFocus(rows.length - 1);
    else if (e.key === 'Enter' || e.key === ' ') {
      const row = rows[index];
      if (row) onRowClick?.(row.original);
    } else return;
    e.preventDefault();
  };

  const hideable = table.getAllLeafColumns().filter((c) => c.getCanHide());

  let body: ReactNode;
  if (error) {
    body = (
      <TableRow className="hover:bg-transparent">
        <TableCell colSpan={columnCount} className="h-auto">
          {error}
        </TableCell>
      </TableRow>
    );
  } else if (loading && rows.length === 0) {
    body = showSkeleton
      ? Array.from({ length: SKELETON_ROWS }, (_, i) => (
          <TableRow key={i} aria-hidden className="hover:bg-transparent">
            {selectable && (
              <TableCell>
                <Skeleton className="size-4" />
              </TableCell>
            )}
            {leafColumns.map((column, c) => (
              <TableCell
                key={column.id}
                className={cn(column.columnDef.meta?.align === 'right' && 'text-right')}
              >
                <Skeleton
                  className={cn(
                    'inline-block h-3',
                    column.columnDef.meta?.align === 'right'
                      ? 'w-12'
                      : ['w-3/4', 'w-1/2', 'w-2/3'][(i + c) % 3],
                  )}
                />
              </TableCell>
            ))}
          </TableRow>
        ))
      : null;
  } else if (rows.length === 0) {
    body = (
      <TableRow className="hover:bg-transparent">
        <TableCell colSpan={columnCount} className="h-auto">
          {empty}
        </TableCell>
      </TableRow>
    );
  } else {
    body = (
      <>
        {padTop > 0 && (
          <tr aria-hidden style={{ height: padTop }}>
            <td colSpan={columnCount} className="p-0" />
          </tr>
        )}
        {visibleIndexes.map((index) => {
          const row = rows[index];
          if (!row) return null;
          const selected = row.getIsSelected();
          return (
            <TableRow
              key={row.id}
              data-index={index}
              data-state={selected ? 'selected' : undefined}
              data-active={row.id === activeRowId || undefined}
              tabIndex={onRowClick ? (index === (focusIndex ?? 0) ? 0 : -1) : undefined}
              onClick={onRowClick ? () => onRowClick(row.original) : undefined}
              onFocus={(e) => e.target === e.currentTarget && setFocusIndex(index)}
              onKeyDown={onRowClick ? (e) => onRowKeyDown(e, index) : undefined}
              className={cn(
                onRowClick && 'cursor-pointer outline-offset-[-2px]',
                'data-active:bg-accent-weak data-active:hover:bg-accent-weak',
              )}
            >
              {selectable && (
                <TableCell onClick={(e) => e.stopPropagation()}>
                  <Checkbox
                    aria-label={`选择第 ${index + 1} 行`}
                    checked={selected}
                    onCheckedChange={(v) => row.toggleSelected(v === true)}
                  />
                </TableCell>
              )}
              {row.getVisibleCells().map((cell) => (
                <TableCell
                  key={cell.id}
                  className={cn('truncate', cell.column.columnDef.meta?.align === 'right' && 'text-right')}
                >
                  <table.FlexRender cell={cell} />
                </TableCell>
              ))}
            </TableRow>
          );
        })}
        {padBottom > 0 && (
          <tr aria-hidden style={{ height: padBottom }}>
            <td colSpan={columnCount} className="p-0" />
          </tr>
        )}
      </>
    );
  }

  const pageIndex = table.state.pagination.pageIndex;
  const total = table.getPrePaginatedRowModel().rows.length;

  return (
    <div data-slot="data-table" className={cn('flex min-h-0 flex-col', className)}>
      <div className="flex min-h-12 items-center gap-3 py-2">
        {selectable && selectedRows.length > 0 ? (
          <div role="toolbar" aria-label="批量操作" className="flex flex-1 items-center gap-3">
            <span className="text-13 font-medium tabular-nums">
              已选 {formatInteger(selectedRows.length)} 项
            </span>
            {bulkActions(
              selectedRows.map((r) => r.original),
              () => table.resetRowSelection(),
            )}
            <Button size="sm" variant="ghost" onClick={() => table.resetRowSelection()}>
              取消选择
            </Button>
          </div>
        ) : (
          <div className="min-w-0 flex-1">{toolbar}</div>
        )}
        {hideable.length > 0 && (
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <Button size="icon-sm" variant="ghost" aria-label="显示的列">
                <Icon glyph={ColumnsIcon} />
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end" className="min-w-44">
              <DropdownMenuLabel>显示的列</DropdownMenuLabel>
              {hideable.map((column) => (
                <DropdownMenuCheckboxItem
                  key={column.id}
                  checked={column.getIsVisible()}
                  onSelect={(e) => e.preventDefault()}
                  onCheckedChange={(v) => column.toggleVisibility(v === true)}
                >
                  {column.columnDef.meta?.label ??
                    (typeof column.columnDef.header === 'string' ? column.columnDef.header : column.id)}
                </DropdownMenuCheckboxItem>
              ))}
            </DropdownMenuContent>
          </DropdownMenu>
        )}
      </div>

      {updates && updates.count > 0 && (
        <button
          type="button"
          onClick={updates.onApply}
          className="mb-2 flex h-8 items-center justify-center rounded-control bg-accent-weak text-13 text-accent transition-colors duration-150 hover:text-accent-hover"
        >
          有 {formatInteger(updates.count)} 条更新，点击刷新
        </button>
      )}

      <div ref={scrollRef} className="min-h-0 flex-1 overflow-auto" aria-busy={loading || undefined}>
        <Table aria-label={label} containerClassName="overflow-visible" className="table-fixed">
          <colgroup>
            {selectable && <col style={{ width: SELECT_COLUMN_WIDTH }} />}
            {leafColumns.map((column) => (
              <col
                key={column.id}
                style={column.columnDef.meta?.width ? { width: column.columnDef.meta.width } : undefined}
              />
            ))}
          </colgroup>
          <TableHeader className="sticky top-0 z-10 bg-surface">
            {table.getHeaderGroups().map((group) => (
              <TableRow key={group.id} className="hover:bg-transparent">
                {selectable && (
                  <TableHead>
                    <Checkbox
                      aria-label="选择本页全部行"
                      checked={
                        table.getIsAllPageRowsSelected()
                          ? true
                          : table.getIsSomePageRowsSelected()
                            ? 'indeterminate'
                            : false
                      }
                      onCheckedChange={(v) => table.toggleAllPageRowsSelected(v === true)}
                    />
                  </TableHead>
                )}
                {group.headers.map((header) => {
                  const sorted = header.column.getIsSorted();
                  const right = header.column.columnDef.meta?.align === 'right';
                  return (
                    <TableHead
                      key={header.id}
                      aria-sort={
                        header.column.getCanSort()
                          ? sorted === 'asc'
                            ? 'ascending'
                            : sorted === 'desc'
                              ? 'descending'
                              : 'none'
                          : undefined
                      }
                      className={cn(right && 'text-right')}
                    >
                      {header.isPlaceholder ? null : header.column.getCanSort() ? (
                        <button
                          type="button"
                          onClick={header.column.getToggleSortingHandler()}
                          className={cn(
                            'inline-flex h-6 items-center gap-1 rounded-control hover:text-ink',
                            right && 'flex-row-reverse',
                            sorted && 'text-ink',
                          )}
                        >
                          <table.FlexRender header={header} />
                          {sorted && <Icon glyph={sorted === 'asc' ? ArrowUpIcon : ArrowDownIcon} />}
                        </button>
                      ) : (
                        <table.FlexRender header={header} />
                      )}
                    </TableHead>
                  );
                })}
              </TableRow>
            ))}
          </TableHeader>
          <TableBody>{body}</TableBody>
        </Table>
      </div>

      {pageSize !== null && total > pageSize && (
        <div className="flex h-12 items-center justify-between border-t border-rule text-12 text-ink-2 tabular-nums">
          <span>
            第 {formatInteger(pageIndex * pageSize + 1)}–
            {formatInteger(Math.min(total, (pageIndex + 1) * pageSize))} 条，共 {formatInteger(total)} 条
          </span>
          <div className="flex items-center gap-2">
            <span>
              第 {pageIndex + 1} / {table.getPageCount()} 页
            </span>
            <Button
              size="icon-sm"
              variant="ghost"
              aria-label="上一页"
              disabled={!table.getCanPreviousPage()}
              onClick={() => table.previousPage()}
            >
              <Icon glyph={CaretLeftIcon} />
            </Button>
            <Button
              size="icon-sm"
              variant="ghost"
              aria-label="下一页"
              disabled={!table.getCanNextPage()}
              onClick={() => table.nextPage()}
            >
              <Icon glyph={CaretRightIcon} />
            </Button>
          </div>
        </div>
      )}

      {isCursor(pagination) && rows.length > 0 && (
        <div className="flex h-12 items-center justify-between border-t border-rule text-12 text-ink-2 tabular-nums">
          <span>已加载 {formatInteger(rows.length)} 条</span>
          {pagination.hasMore && (
            <Button size="sm" pending={pagination.loadingMore} onClick={pagination.onLoadMore}>
              加载更多
            </Button>
          )}
        </div>
      )}
    </div>
  );
}
