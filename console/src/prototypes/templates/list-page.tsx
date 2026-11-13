import type { ReactNode } from 'react';

import { PageHeader } from '@/components/domain/page-header';

export interface ListPageProps {
  title: ReactNode;
  /** 页面的主操作（"新建智能体"）。 */
  actions?: ReactNode;
  /** 标题下的标签页（"待回应""历史"），可选。 */
  tabs?: ReactNode;
  /** 表格（DataTable，工具栏里是 FilterBar），占满剩余高度，表头吸顶、表体滚动。 */
  children: ReactNode;
}

/**
 * 列表页模板：页头、筛选栏、数据表格。宽表格页满宽，左右 24px；表格自己滚动，页头与筛选栏不随之移走。
 */
export function ListPage({ title, actions, tabs, children }: ListPageProps) {
  return (
    <div className="flex h-full min-h-0 flex-col bg-surface px-6">
      <PageHeader title={title} actions={actions} className={tabs ? 'pb-0' : 'pb-2'}>
        {tabs}
      </PageHeader>
      <div className="flex min-h-0 flex-1 flex-col pb-2">{children}</div>
    </div>
  );
}
