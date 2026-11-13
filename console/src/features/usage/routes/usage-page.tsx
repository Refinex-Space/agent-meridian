import { EmptyState } from '@/components/domain/empty-state';
import { DetailPage } from '@/components/templates';

/**
 * 用量与预算（T25b）：账单、明细、预算设置与告警。
 * 页面内容由对应的页面任务实现，现在是首次使用的空态。
 */
export function UsagePage() {
  return (
    <DetailPage title="用量与预算">
      <EmptyState variant="first-use" title="用量、账单与预算设置会出现在这里" />
    </DetailPage>
  );
}
