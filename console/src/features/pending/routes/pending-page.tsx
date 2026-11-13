import { EmptyState } from '@/components/domain/empty-state';
import { ListPage } from '@/components/templates';

/**
 * 待处理（T25a）：等待回应的动作与历史。
 * 页面内容由对应的页面任务实现，现在是首次使用的空态。
 */
export function PendingPage() {
  return (
    <ListPage title="待处理">
      <EmptyState variant="first-use" title="需要回应的待处理动作会出现在这里" />
    </ListPage>
  );
}
