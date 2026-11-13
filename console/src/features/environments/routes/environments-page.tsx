import { EmptyState } from '@/components/domain/empty-state';
import { ListPage } from '@/components/templates';

/**
 * 环境（T22）：执行档位、沙箱规格、出网白名单与修订历史。
 * 页面内容由对应的页面任务实现，现在是首次使用的空态。
 */
export function EnvironmentsPage() {
  return (
    <ListPage title="环境">
      <EmptyState variant="first-use" title="本项目的执行环境会出现在这里" />
    </ListPage>
  );
}
