import { useQuery } from '@tanstack/react-query';
import { Outlet } from '@tanstack/react-router';

import { PermissionGate } from '@/components/domain/permission-gate';
import { DetailPage } from '@/components/templates';
import { usePermission, useProject } from '@/lib/access';
import { useCrumb } from '@/lib/crumbs';

import { agentQuery } from '../api/queries';

/**
 * 单个智能体的路由（详情、编辑、对比）的外层：面包屑显示智能体的名字（"智能体 / support-assistant / 编辑"）。
 * 名字到达之前不显示这一级；读取失败由内页的区域显示。
 */
export function AgentLayout({ agentId }: { agentId: string }) {
  const { tenantId, projectId } = useProject();
  const read = usePermission('agent_read', '查看智能体');
  const name = useQuery({ ...agentQuery({ tenantId, projectId }, agentId), enabled: read.allowed }).data
    ?.name;
  useCrumb(name);
  if (!read.allowed)
    return (
      <DetailPage title="智能体">
        <PermissionGate mode="explain" title="没有权限查看智能体" allowed={false} reason={read.reason} />
      </DetailPage>
    );
  return <Outlet />;
}
