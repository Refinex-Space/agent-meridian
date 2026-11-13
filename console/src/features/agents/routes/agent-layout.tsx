import { useQuery } from '@tanstack/react-query';
import { Outlet } from '@tanstack/react-router';

import { useProject } from '@/lib/access';
import { useCrumb } from '@/lib/crumbs';

import { agentQuery } from '../api/queries';

/**
 * 单个智能体的路由（详情、编辑、对比）的外层：面包屑显示智能体的名字（"智能体 / support-assistant / 编辑"）。
 * 名字到达之前不显示这一级；读取失败由内页的区域显示。
 */
export function AgentLayout({ agentId }: { agentId: string }) {
  const { tenantId, projectId } = useProject();
  const name = useQuery(agentQuery({ tenantId, projectId }, agentId)).data?.name;
  useCrumb(name);
  return <Outlet />;
}
