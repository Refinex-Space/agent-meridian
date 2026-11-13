import { EmptyState } from '@/components/domain/empty-state';
import { ListPage } from '@/components/templates';

/**
 * 智能体（T22）：列表、定义编辑、版本发布与对比、子智能体拓扑。
 * 页面内容由对应的页面任务实现，现在是首次使用的空态。
 */
export function AgentsPage() {
  return (
    <ListPage title="智能体">
      <EmptyState variant="first-use" title="本项目的智能体与版本会出现在这里" />
    </ListPage>
  );
}
