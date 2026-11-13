import { EmptyState } from '@/components/domain/empty-state';
import { DetailPage } from '@/components/templates';

/**
 * 模型（T23）：供应商账号、模型目录、价格表。
 * 页面内容由对应的页面任务实现，现在是首次使用的空态。
 */
export function ModelsPage() {
  return (
    <DetailPage title="模型">
      <EmptyState variant="first-use" title="租户的供应商账号、模型目录与价格表会出现在这里" />
    </DetailPage>
  );
}
