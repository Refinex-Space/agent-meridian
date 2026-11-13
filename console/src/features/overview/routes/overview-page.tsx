import { EmptyState } from '@/components/domain/empty-state';
import { DetailPage } from '@/components/templates';

/**
 * 概览（T25a）：运行状况、按智能体的失败率、最近错误、用量趋势、本月预算。
 * 页面内容由对应的页面任务实现，现在是首次使用的空态。
 */
export function OverviewPage() {
  return (
    <DetailPage title="概览">
      <EmptyState variant="first-use" title="本项目的运行状况、用量趋势与预算消耗会出现在这里" />
    </DetailPage>
  );
}
