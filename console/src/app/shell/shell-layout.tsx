import { useQuery, useQueryClient, useSuspenseQuery } from '@tanstack/react-query';
import { Outlet, useMatches, useNavigate } from '@tanstack/react-router';
import { useMemo } from 'react';

import {
  describeRoles,
  hasPermission,
  meQuery,
  projectsQuery,
  ProjectProvider,
  type CurrentProject,
} from '@/lib/access';
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
      >
        <Outlet />
      </AppShell>
    </ProjectProvider>
  );
}
