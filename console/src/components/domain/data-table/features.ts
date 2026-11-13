/**
 * DataTable 用到的 TanStack Table 9 功能：排序、分页、列显隐、行选择。9 的功能与行模型都要在这里显式登记，
 * 列定义用 `dataTableColumns<T>()` 的列助手写，类型随之带上这些功能与列元数据。
 */
import {
  columnVisibilityFeature,
  createColumnHelper,
  createPaginatedRowModel,
  createSortedRowModel,
  rowPaginationFeature,
  rowSelectionFeature,
  rowSortingFeature,
  sortFn_alphanumeric,
  sortFn_basic,
  sortFn_datetime,
  tableFeatures,
  type ColumnHelper,
  type RowData,
} from '@tanstack/react-table';

/** 列的展示设置。 */
export interface DataTableColumnMeta {
  /** 列显隐菜单里的名称；表头不是纯文字时必填。 */
  label?: string;
  /** 数字列右对齐。 */
  align?: 'left' | 'right';
  /** 列宽（px）。表格用固定布局，虚拟滚动时列宽不随可见行跳动；不给的列平分剩余宽度。 */
  width?: number;
}

/** 列元数据的类型槽：TanStack Table 9 从这个值的类型推断 `columnDef.meta`，值本身不被使用。 */
const columnMeta: DataTableColumnMeta = {};

export const dataTableFeatures = tableFeatures({
  rowSortingFeature,
  rowPaginationFeature,
  columnVisibilityFeature,
  rowSelectionFeature,
  sortedRowModel: createSortedRowModel(),
  paginatedRowModel: createPaginatedRowModel(),
  sortFns: { basic: sortFn_basic, alphanumeric: sortFn_alphanumeric, datetime: sortFn_datetime },
  columnMeta,
});

export type DataTableFeatures = typeof dataTableFeatures;

/** 列助手：`const col = dataTableColumns<Session>(); const columns = col.columns([col.accessor('id', {...})])`。 */
export function dataTableColumns<TData extends RowData>(): ColumnHelper<DataTableFeatures, TData> {
  return createColumnHelper<DataTableFeatures, TData>();
}

/** 列定义数组（列助手 `columns()` 的结果）。 */
export type DataTableColumns<TData extends RowData> = ReturnType<
  ColumnHelper<DataTableFeatures, TData>['columns']
>;
