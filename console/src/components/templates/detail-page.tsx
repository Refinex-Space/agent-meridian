import type { ReactNode } from 'react';

import { PageHeader } from '@/components/domain/page-header';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';

export interface DetailTab {
  value: string;
  label: ReactNode;
  content: ReactNode;
}

export interface DetailPageProps {
  title: ReactNode;
  /** 标题下一行：状态、ID、更新时间，分列对齐。 */
  meta?: ReactNode;
  /** 主操作在最右。 */
  actions?: ReactNode;
  /** 加载中：页头显示骨架，标签页内容由各自的区域决定。 */
  loading?: boolean;
  /** 标签页；只有一页内容时不传，直接给 `children`。 */
  tabs?: readonly DetailTab[];
  defaultTab?: string;
  /** 受控的当前标签页（放在搜索参数里时）；不给时由模板自己记住。 */
  tab?: string;
  onTabChange?: (tab: string) => void;
  children?: ReactNode;
}

/**
 * 详情页模板：页头（状态与主操作）、标签页、属性列表与子表格。内容左对齐，整页滚动，页头不吸顶。
 */
export function DetailPage({
  title,
  meta,
  actions,
  loading,
  tabs,
  defaultTab,
  tab,
  onTabChange,
  children,
}: DetailPageProps) {
  if (!tabs) {
    return (
      <div className="min-h-full bg-surface px-6 pb-12">
        <PageHeader title={title} meta={meta} actions={actions} loading={loading} />
        {children}
      </div>
    );
  }
  return (
    <Tabs
      defaultValue={defaultTab ?? tabs[0]?.value}
      {...(tab !== undefined && { value: tab })}
      {...(onTabChange && { onValueChange: onTabChange })}
      className="min-h-full gap-0 bg-surface px-6 pb-12"
    >
      <PageHeader title={title} meta={meta} actions={actions} loading={loading}>
        <TabsList>
          {tabs.map((tab) => (
            <TabsTrigger key={tab.value} value={tab.value}>
              {tab.label}
            </TabsTrigger>
          ))}
        </TabsList>
      </PageHeader>
      {tabs.map((tab) => (
        <TabsContent key={tab.value} value={tab.value} className="pt-6">
          {tab.content}
        </TabsContent>
      ))}
    </Tabs>
  );
}
