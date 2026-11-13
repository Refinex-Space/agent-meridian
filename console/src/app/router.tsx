/**
 * 路由树（TanStack Router，代码定义）：
 *
 * /                              → 跳到上次使用的项目（没有时取第一个）的概览；不是任何项目的成员时说明原因
 * /projects/$projectId           → 确认用户能进入该项目、取权限后渲染应用外壳（ShellLayout）
 *   ├ overview … settings        → 十一个一级导航，各自一个路由模块（features/<领域>）
 *   └ $                          → 项目内不存在的页面
 *
 * 授权码回调与静默登录回调在路由之前由 bootstrap 处理（ADR-0055）。
 */
import type { QueryClient } from '@tanstack/react-query';
import {
  createRootRouteWithContext,
  createRoute,
  createRouter,
  notFound,
  Outlet,
  redirect,
} from '@tanstack/react-router';

import { AgentsPage } from '@/features/agents';
import { CapabilitiesPage } from '@/features/capabilities';
import { EnvironmentsPage } from '@/features/environments';
import { KnowledgePage } from '@/features/knowledge';
import { ModelsPage } from '@/features/models';
import { OverviewPage } from '@/features/overview';
import { PendingPage } from '@/features/pending';
import { PlaygroundPage } from '@/features/playground';
import { SessionsPage } from '@/features/sessions';
import { SettingsPage } from '@/features/settings';
import { UsagePage } from '@/features/usage';
import {
  accessQuery,
  meQuery,
  projectsQuery,
  rolesInProject,
  type CurrentProject,
  type TenantProjects,
} from '@/lib/access';
import { parseSearch, stringifySearch } from '@/lib/search';
import type { ReactNode } from 'react';

import { FullPage, NotFound } from './boot-screens';
import { HomeRedirectFailed, NoProjectsPage, ProjectErrorPage } from './route-states';
import type { NavKey } from './shell/nav';
import { ShellLayout } from './shell/shell-layout';

export interface RouterContext {
  queryClient: QueryClient;
}

declare module '@tanstack/react-router' {
  interface StaticDataRouteOption {
    /** 页面所在的一级导航，外壳据此高亮导航项。 */
    nav?: NavKey;
    /** 页面在面包屑中的名称。 */
    crumb?: string;
  }
}

const LAST_PROJECT_KEY = 'meridian.project.last';

function lastProject(): string | null {
  try {
    return window.localStorage.getItem(LAST_PROJECT_KEY);
  } catch {
    return null;
  }
}

function rememberProject(projectId: string): void {
  try {
    window.localStorage.setItem(LAST_PROJECT_KEY, projectId);
  } catch {
    // 无痕模式或禁用了存储：下次打开时回到第一个项目。
  }
}

/** 首页去哪个项目：上次使用的项目仍可进入时回到它，否则取第一个。 */
export function pickProject(groups: readonly TenantProjects[], last: string | null): string | null {
  const all = groups.flatMap((g) => g.projects);
  return (all.find((p) => p.id === last) ?? all[0])?.id ?? null;
}

const rootRoute = createRootRouteWithContext<RouterContext>()({
  component: Outlet,
  notFoundComponent: () => (
    <FullPage>
      <NotFound />
    </FullPage>
  ),
});

const homeRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/',
  beforeLoad: async ({ context }) => {
    const me = await context.queryClient.ensureQueryData(meQuery());
    const groups = await context.queryClient.ensureQueryData(projectsQuery(me));
    const projectId = pickProject(groups, lastProject());
    if (projectId)
      throw redirect({ to: '/projects/$projectId/overview', params: { projectId }, replace: true });
  },
  component: NoProjectsPage,
  errorComponent: HomeRedirectFailed,
});

const projectRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/projects/$projectId',
  beforeLoad: async ({ context, params }): Promise<{ project: CurrentProject }> => {
    const { queryClient } = context;
    const me = await queryClient.ensureQueryData(meQuery());
    const groups = await queryClient.ensureQueryData(projectsQuery(me));
    const group = groups.find((g) => g.projects.some((p) => p.id === params.projectId));
    const entry = group?.projects.find((p) => p.id === params.projectId);
    if (!group || !entry) throw notFound();
    const access = await queryClient.ensureQueryData(accessQuery(entry.tenant_id, entry.id));
    rememberProject(entry.id);
    return {
      project: {
        tenantId: entry.tenant_id,
        tenantName: group.tenant_name,
        projectId: entry.id,
        projectName: entry.name,
        roles: rolesInProject(me, entry.tenant_id, entry.id),
        access,
      },
    };
  },
  component: function ProjectLayout() {
    const { project } = projectRoute.useRouteContext();
    return <ShellLayout project={project} />;
  },
  errorComponent: ProjectErrorPage,
  notFoundComponent: () => (
    <FullPage>
      <NotFound title="这个项目不存在，或你没有进入它的权限" />
    </FullPage>
  ),
});

const projectHomeRoute = createRoute({
  getParentRoute: () => projectRoute,
  path: '/',
  beforeLoad: ({ params }) => {
    throw redirect({ to: '/projects/$projectId/overview', params, replace: true });
  },
});

/** 一级导航的路由：每项一个路由模块，页面组件来自 `features/<领域>`。 */
function navRoute<P extends string>(path: P, nav: NavKey, crumb: string, component: () => ReactNode) {
  return createRoute({
    getParentRoute: () => projectRoute,
    path,
    staticData: { nav, crumb },
    component,
  });
}

const overviewRoute = navRoute('overview', 'overview', '概览', OverviewPage);
const sessionsRoute = navRoute('sessions', 'sessions', '会话', SessionsPage);
const playgroundRoute = navRoute('playground', 'playground', 'Playground', PlaygroundPage);
const agentsRoute = navRoute('agents', 'agents', '智能体', AgentsPage);
const environmentsRoute = navRoute('environments', 'environments', '环境', EnvironmentsPage);
const capabilitiesRoute = navRoute('capabilities', 'capabilities', '能力', CapabilitiesPage);
const knowledgeRoute = navRoute('knowledge', 'knowledge', '知识库', KnowledgePage);
const modelsRoute = navRoute('models', 'models', '模型', ModelsPage);
const pendingRoute = navRoute('pending', 'pending', '待处理', PendingPage);
const usageRoute = navRoute('usage', 'usage', '用量与预算', UsagePage);
const settingsRoute = navRoute('settings', 'settings', '设置', SettingsPage);

const projectNotFoundRoute = createRoute({
  getParentRoute: () => projectRoute,
  path: '$',
  staticData: { crumb: '页面不存在' },
  component: () => <NotFound />,
});

export const routeTree = rootRoute.addChildren([
  homeRoute,
  projectRoute.addChildren([
    projectHomeRoute,
    overviewRoute,
    sessionsRoute,
    playgroundRoute,
    agentsRoute,
    environmentsRoute,
    capabilitiesRoute,
    knowledgeRoute,
    modelsRoute,
    pendingRoute,
    usageRoute,
    settingsRoute,
    projectNotFoundRoute,
  ]),
]);

export function createAppRouter(queryClient: QueryClient) {
  return createRouter({
    routeTree,
    context: { queryClient },
    // 搜索参数用标准的 key=value 与重复键（lib/search.ts），不用默认的 JSON 编码。
    parseSearch,
    stringifySearch,
    // 路由数据由 TanStack Query 缓存，路由自己不再缓存 beforeLoad 的结果之外的数据。
    defaultPreloadStaleTime: 0,
    scrollRestoration: true,
  });
}

declare module '@tanstack/react-router' {
  interface Register {
    router: ReturnType<typeof createAppRouter>;
  }
}
