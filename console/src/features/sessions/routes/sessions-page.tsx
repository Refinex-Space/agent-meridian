import { Link } from '@tanstack/react-router';

import { EmptyState } from '@/components/domain/empty-state';
import { PermissionGate } from '@/components/domain/permission-gate';
import { ListPage } from '@/components/templates';
import { Button } from '@/components/ui/button';
import { FlaskIcon } from '@/components/ui/glyphs';
import { Icon } from '@/components/ui/icon';
import { usePermission, useProject } from '@/lib/access';

/**
 * 会话（T24）：会话列表与筛选、单会话的事件时间轴、回放。页面内容由 T24 实现，现在是首次使用的空态；
 * 主操作"在 Playground 中对话"需要创建会话的权限。
 */
export function SessionsPage() {
  const { projectId } = useProject();
  const playground = usePermission('session_write', '在 Playground 中对话');
  return (
    <ListPage title="会话">
      <EmptyState
        variant="first-use"
        title="业务系统调用会话接口、或在 Playground 中对话后，会话出现在这里"
        action={
          <PermissionGate mode="disable" {...playground}>
            <Button variant="primary" asChild>
              <Link to="/projects/$projectId/playground" params={{ projectId }}>
                <Icon glyph={FlaskIcon} />在 Playground 中对话
              </Link>
            </Button>
          </PermissionGate>
        }
      />
    </ListPage>
  );
}
