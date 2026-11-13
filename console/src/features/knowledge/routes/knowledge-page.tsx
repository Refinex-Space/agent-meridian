import { EmptyState } from '@/components/domain/empty-state';
import { ListPage } from '@/components/templates';

/**
 * 知识库（T25a）：知识库、文档写入进度、检索测试。
 * 页面内容由对应的页面任务实现，现在是首次使用的空态。
 */
export function KnowledgePage() {
  return (
    <ListPage title="知识库">
      <EmptyState variant="first-use" title="本项目的知识库会出现在这里" />
    </ListPage>
  );
}
