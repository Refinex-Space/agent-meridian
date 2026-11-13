import { useQuery, useQueryClient, useSuspenseQuery } from '@tanstack/react-query';
import { Outlet, useMatches, useNavigate } from '@tanstack/react-router';
import { useMemo, useState } from 'react';

import {
  describeRoles,
  hasPermission,
  meQuery,
  projectsQuery,
  ProjectProvider,
  type CurrentProject,
} from '@/lib/access';
import { AgentGlyph } from '@/components/ui/glyphs';
import { adminApi, unwrap, v1Api } from '@/lib/api/client';
import { useAuth } from '@/lib/auth/auth-context';
import { CrumbProvider, useCrumbLabels } from '@/lib/crumbs';
import { setThemePreference, useThemePreference } from '@/lib/theme';

import { AppShell, type ShellCrumb } from './app-shell';
import { BudgetBanner } from './budget-banner';
import { ConnectionBanner } from './connection-banner';
import { NAV, type NavKey } from './nav';

/** 导航上的待处理动作数（console-api-map.md「应用外壳」：轮询 30 秒）。 */
function usePendingCount(projectId: string): number {
  const { data } = useQuery({
    queryKey: ['v1', projectId, 'session-stats', '24h'],
    queryFn: ({ signal }) =>
      unwrap(v1Api(projectId).GET('/v1/session-stats', { params: { query: { window: '24h' } }, signal })),
    refetchInterval: 30_000,
    select: (stats) => stats.pending_actions,
  });
  return data ?? 0;
}

/** 预算告警（轮询 5 分钟）；没有查看用量的权限时不查询。 */
function useBudgetStatus(project: CurrentProject) {
  const { tenantId, projectId } = project;
  return useQuery({
    queryKey: ['admin', tenantId, projectId, 'budget-status'],
    queryFn: ({ signal }) =>
      unwrap(
        adminApi().GET('/admin/v1/tenants/{tenant_id}/projects/{project_id}/budget-status', {
          params: { path: { tenant_id: tenantId, project_id: projectId } },
          signal,
        }),
      ),
    refetchInterval: 5 * 60_000,
    enabled: hasPermission(project, 'usage_read'),
  }).data;
}

/**
 * 项目路由的布局：把路由（当前导航、面包屑、跳转）、身份、项目列表、待处理计数与预算告警接入 `AppShell`，页面渲染在
 * 其内容区，并经 `ProjectProvider` 拿到当前项目与权限。
 */
export function ShellLayout({ project }: { project: CurrentProject }) {
  return (
    <CrumbProvider>
      <Shell project={project} />
    </CrumbProvider>
  );
}

function Shell({ project }: { project: CurrentProject }) {
  const { user, logout } = useAuth();
  const theme = useThemePreference();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const matches = useMatches();
  // 项目路由的 beforeLoad 已把身份与项目列表放进缓存，这里不会挂起。
  const me = useSuspenseQuery(meQuery()).data;
  const projects = useSuspenseQuery(projectsQuery(me)).data;
  const pendingCount = usePendingCount(project.projectId);
  const budget = useBudgetStatus(project);

  const active: NavKey | null = matches.findLast((m) => m.staticData.nav)?.staticData.nav ?? null;
  const dynamic = useCrumbLabels();
  const breadcrumb = useMemo(() => {
    // 动态名称（对象的名字）优先于路由的静态名称；两者都没有的路由不出现在面包屑里。
    const crumbs = matches.filter((m) => dynamic[m.routeId] ?? m.staticData.crumb);
    return crumbs.map((m, i): ShellCrumb => ({
      label: dynamic[m.routeId] ?? m.staticData.crumb ?? '',
      ...(i < crumbs.length - 1 ? { href: m.pathname } : {}),
    }));
  }, [matches, dynamic]);

  const activeItem = NAV.find((item) => item.key === active);

  // ⌘K 的智能体：第一次打开命令面板时才加载，没有查看智能体的权限时不加载（D97）。智能体页的代码按路由懒加载，
  // 这里同样在用到时才导入它（首屏不带表单与校验的代码），数据经它的查询读取、与智能体页共用缓存。
  const [commandUsed, setCommandUsed] = useState(false);
  const agents = useQuery({
    queryKey: ['shell', 'command-agents', project.tenantId, project.projectId],
    queryFn: async () => {
      const { agentsQuery } = await import('@/features/agents');
      return queryClient.fetchQuery(
        agentsQuery({ tenantId: project.tenantId, projectId: project.projectId }),
      );
    },
    enabled: commandUsed && hasPermission(project, 'agent_read'),
  }).data;
  const commandGroups = useMemo(
    () => [
      {
        heading: '智能体',
        items: (agents ?? []).map((agent) => ({
          key: agent.id,
          label: agent.name,
          detail: agent.current_version ? `v${agent.current_version.version}` : '未发布',
          glyph: AgentGlyph,
          onSelect: () =>
            void navigate({
              to: '/projects/$projectId/agents/$agentId',
              params: { projectId: project.projectId, agentId: agent.id },
            }),
        })),
      },
    ],
    [agents, navigate, project.projectId],
  );

  return (
    <ProjectProvider project={project}>
      <AppShell
        active={active}
        project={{ id: project.projectId, name: project.projectName }}
        projects={projects}
        onSwitchProject={(projectId) =>
          void navigate({ to: activeItem?.to ?? '/projects/$projectId/overview', params: { projectId } })
        }
        user={{ name: user.name, email: user.email, roles: describeRoles(project.roles) }}
        theme={theme}
        onThemeChange={setThemePreference}
        onLogout={() => void logout()}
        pendingCount={pendingCount}
        breadcrumb={breadcrumb}
        banner={
          <>
            <ConnectionBanner onRetry={() => void queryClient.refetchQueries({ type: 'active' })} />
            {budget && <BudgetBanner projectId={project.projectId} status={budget} />}
          </>
        }
        onNavigate={(item) => void navigate({ to: item.to, params: { projectId: project.projectId } })}
        commandGroups={commandGroups}
        onCommandOpen={() => setCommandUsed(true)}
      >
        <Outlet />
      </AppShell>
    </ProjectProvider>
  );
}
