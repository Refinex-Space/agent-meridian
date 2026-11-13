import { EmptyState } from '@/components/domain/empty-state';
import { PermissionGate } from '@/components/domain/permission-gate';
import { DetailPage } from '@/components/templates';
import { usePermission } from '@/lib/access';

/**
 * Playground（T24）：选择已发布的智能体与环境直接对话，实时查看事件流与工作区。页面内容由 T24 实现；没有创建会话的
 * 权限时整页说明原因。
 */
export function PlaygroundPage() {
  const chat = usePermission('session_write', '在 Playground 中对话');
  return (
    <DetailPage title="Playground">
      {chat.allowed ? (
        <EmptyState variant="first-use" title="选择已发布的智能体与环境后，在这里直接对话" />
      ) : (
        <PermissionGate mode="explain" title="没有权限使用 Playground" allowed={false} reason={chat.reason} />
      )}
    </DetailPage>
  );
}
