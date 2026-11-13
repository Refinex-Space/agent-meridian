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

import type { AgentDetailTab } from '@/features/agents';
import type { CapabilitiesTab } from '@/features/capabilities';
import { KnowledgePage } from '@/features/knowledge';
import type { ModelsTab } from '@/features/models';
import { OverviewPage } from '@/features/overview';
import { PendingPage } from '@/features/pending';
import { PlaygroundPage } from '@/features/playground';
import { SessionsPage } from '@/features/sessions';
import { UsagePage } from '@/features/usage';
import {
  accessQuery,
  meQuery,
  projectsQuery,
  rolesInProject,
  type CurrentProject,
  type TenantProjects,
} from '@/lib/access';
import { parseSearch, searchString, stringifySearch } from '@/lib/search';
import { lazy, type ReactNode } from 'react';

import { FullPage, NotFound } from './boot-screens';
import { HomeRedirectFailed, NoProjectsPage, ProjectErrorPage } from './route-states';
import type { NavKey } from './shell/nav';
import { ShellLayout } from './shell/shell-layout';

/**
 * 智能体与环境的页面按路由懒加载（frontend-design.md「性能预算」：首屏 JavaScript gzip 后不超过 300 KB）：表单、校验与差异
 * 只在进入这两个导航时下载；Monaco 与 xyflow 在页面内再各自懒加载。
 */
const agentsFeature = () => import('@/features/agents');
const environmentsFeature = () => import('@/features/environments');
const AgentListPage = lazy(() => agentsFeature().then((m) => ({ default: m.AgentListPage })));
const AgentLayout = lazy(() => agentsFeature().then((m) => ({ default: m.AgentLayout })));
const AgentDetailPage = lazy(() => agentsFeature().then((m) => ({ default: m.AgentDetailPage })));
const AgentEditPage = lazy(() => agentsFeature().then((m) => ({ default: m.AgentEditPage })));
const AgentComparePage = lazy(() => agentsFeature().then((m) => ({ default: m.AgentComparePage })));
const EnvironmentListPage = lazy(() =>
  environmentsFeature().then((m) => ({ default: m.EnvironmentListPage })),
);
const EnvironmentEditPage = lazy(() =>
  environmentsFeature().then((m) => ({ default: m.EnvironmentEditPage })),
);
/** 能力与设置（T23）同样按路由懒加载：技能包的 YAML 解析、凭据的对话框只在进入时下载。 */
const capabilitiesFeature = () => import('@/features/capabilities');
const settingsFeature = () => import('@/features/settings');
const CapabilitiesPage = lazy(() => capabilitiesFeature().then((m) => ({ default: m.CapabilitiesPage })));
const SkillDetailPage = lazy(() => capabilitiesFeature().then((m) => ({ default: m.SkillDetailPage })));
const McpServerDetailPage = lazy(() =>
  capabilitiesFeature().then((m) => ({ default: m.McpServerDetailPage })),
);
const modelsFeature = () => import('@/features/models');
const ModelsPage = lazy(() => modelsFeature().then((m) => ({ default: m.ModelsPage })));
const PriceBookDetailPage = lazy(() => modelsFeature().then((m) => ({ default: m.PriceBookDetailPage })));
const SecretsPage = lazy(() => settingsFeature().then((m) => ({ default: m.SecretsPage })));

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
const knowledgeRoute = navRoute('knowledge', 'knowledge', '知识库', KnowledgePage);
const pendingRoute = navRoute('pending', 'pending', '待处理', PendingPage);
const usageRoute = navRoute('usage', 'usage', '用量与预算', UsagePage);

/** 能力（T23）：技能、MCP 服务、自定义工具三个标签页（搜索参数 `tab`），技能与 MCP 服务各有详情页。 */
const CAPABILITY_TABS: readonly CapabilitiesTab[] = ['skills', 'mcp-servers', 'custom-tools'];
const capabilitiesRoute = navRoute('capabilities', 'capabilities', '能力', () => <Outlet />);
const capabilityListRoute = createRoute({
  getParentRoute: () => capabilitiesRoute,
  path: '/',
  validateSearch: (search: Record<string, unknown>): { tab?: CapabilitiesTab } => {
    const tab = searchString(search['tab']) as CapabilitiesTab | undefined;
    return tab && CAPABILITY_TABS.includes(tab) && tab !== 'skills' ? { tab } : {};
  },
  component: function CapabilityListRoute() {
    const { tab } = capabilityListRoute.useSearch();
    const navigate = capabilityListRoute.useNavigate();
    return (
      <CapabilitiesPage
        tab={tab ?? 'skills'}
        onTabChange={(next) =>
          void navigate({ search: next === 'skills' ? {} : { tab: next }, replace: true })
        }
      />
    );
  },
});
const skillRoute = createRoute({
  getParentRoute: () => capabilitiesRoute,
  path: 'skills/$skillId',
  component: function SkillRoute() {
    const { skillId } = skillRoute.useParams();
    return <SkillDetailPage key={skillId} skillId={skillId} />;
  },
});
const mcpServerRoute = createRoute({
  getParentRoute: () => capabilitiesRoute,
  path: 'mcp-servers/$mcpServerId',
  component: function McpServerRoute() {
    const { mcpServerId } = mcpServerRoute.useParams();
    return <McpServerDetailPage key={mcpServerId} mcpServerId={mcpServerId} />;
  },
});

/** 模型（T23）：供应商账号、模型目录、价格表三个标签页（搜索参数 `tab`），价格表有详情页（版本历史）。 */
const MODELS_TABS: readonly ModelsTab[] = ['providers', 'catalog', 'price-books'];
const modelsRoute = navRoute('models', 'models', '模型', () => <Outlet />);
const modelListRoute = createRoute({
  getParentRoute: () => modelsRoute,
  path: '/',
  validateSearch: (search: Record<string, unknown>): { tab?: ModelsTab } => {
    const tab = searchString(search['tab']) as ModelsTab | undefined;
    return tab && MODELS_TABS.includes(tab) && tab !== 'providers' ? { tab } : {};
  },
  component: function ModelListRoute() {
    const { tab } = modelListRoute.useSearch();
    const navigate = modelListRoute.useNavigate();
    return (
      <ModelsPage
        tab={tab ?? 'providers'}
        onTabChange={(next) =>
          void navigate({ search: next === 'providers' ? {} : { tab: next }, replace: true })
        }
      />
    );
  },
});
const priceBookRoute = createRoute({
  getParentRoute: () => modelsRoute,
  path: 'price-books/$priceBookId',
  component: function PriceBookRoute() {
    const { priceBookId } = priceBookRoute.useParams();
    return <PriceBookDetailPage key={priceBookId} priceBookId={priceBookId} />;
  },
});

/** 设置（T25b 补全其余页面）：T23 起有凭据；设置的首页暂时就是凭据。 */
const settingsRoute = navRoute('settings', 'settings', '设置', () => <Outlet />);
const settingsHomeRoute = createRoute({
  getParentRoute: () => settingsRoute,
  path: '/',
  beforeLoad: ({ params }) => {
    throw redirect({ to: '/projects/$projectId/settings/secrets', params, replace: true });
  },
});
const secretsRoute = createRoute({
  getParentRoute: () => settingsRoute,
  path: 'secrets',
  staticData: { crumb: '凭据' },
  component: () => <SecretsPage />,
});

/** 智能体（T22）：列表、单个智能体的详情、编辑草稿与版本对比；单个智能体这一级在面包屑中显示它的名字。 */
const agentsRoute = navRoute('agents', 'agents', '智能体', () => <Outlet />);
const agentListRoute = createRoute({
  getParentRoute: () => agentsRoute,
  path: '/',
  component: AgentListPage,
});
const agentRoute = createRoute({
  getParentRoute: () => agentsRoute,
  path: '$agentId',
  component: function AgentRoute() {
    return <AgentLayout agentId={agentRoute.useParams().agentId} />;
  },
});
const AGENT_TABS: readonly AgentDetailTab[] = ['summary', 'versions', 'sessions', 'subagents'];
const agentDetailRoute = createRoute({
  getParentRoute: () => agentRoute,
  path: '/',
  validateSearch: (search: Record<string, unknown>): { tab?: AgentDetailTab } => {
    const tab = searchString(search['tab']) as AgentDetailTab | undefined;
    return tab && AGENT_TABS.includes(tab) ? { tab } : {};
  },
  component: function AgentDetailRoute() {
    const { agentId } = agentDetailRoute.useParams();
    const { tab } = agentDetailRoute.useSearch();
    const navigate = agentDetailRoute.useNavigate();
    return (
      <AgentDetailPage
        agentId={agentId}
        tab={tab ?? 'summary'}
        onTabChange={(next) =>
          void navigate({ search: next === 'summary' ? {} : { tab: next }, replace: true })
        }
      />
    );
  },
});
const agentEditRoute = createRoute({
  getParentRoute: () => agentRoute,
  path: 'versions/$version/edit',
  staticData: { crumb: '编辑' },
  component: function AgentEditRoute() {
    const { agentId, version } = agentEditRoute.useParams();
    const number = Number(version);
    if (!Number.isInteger(number) || number < 1) throw notFound();
    return <AgentEditPage key={`${agentId}-${number}`} agentId={agentId} version={number} />;
  },
});
const agentCompareRoute = createRoute({
  getParentRoute: () => agentRoute,
  path: 'compare',
  staticData: { crumb: '版本对比' },
  validateSearch: (search: Record<string, unknown>): { base?: string; target?: string } => {
    const base = searchString(search['base']);
    const target = searchString(search['target']);
    return { ...(base ? { base } : {}), ...(target ? { target } : {}) };
  },
  component: function AgentCompareRoute() {
    const { agentId } = agentCompareRoute.useParams();
    const { base, target } = agentCompareRoute.useSearch();
    const navigate = agentCompareRoute.useNavigate();
    const number = (v: string | undefined) => (v && /^\d+$/.test(v) ? Number(v) : null);
    return (
      <AgentComparePage
        agentId={agentId}
        base={number(base)}
        target={number(target)}
        onChange={(next) =>
          void navigate({ search: { base: String(next.base), target: String(next.target) }, replace: true })
        }
      />
    );
  },
});

/** 环境（T22）：列表、新建与编辑（含修订历史）；编辑页在面包屑中显示环境的名字。 */
const environmentsRoute = navRoute('environments', 'environments', '环境', () => <Outlet />);
const environmentListRoute = createRoute({
  getParentRoute: () => environmentsRoute,
  path: '/',
  component: () => <EnvironmentListPage />,
});
const environmentNewRoute = createRoute({
  getParentRoute: () => environmentsRoute,
  path: 'new',
  staticData: { crumb: '新建' },
  component: () => <EnvironmentEditPage />,
});
const environmentEditRoute = createRoute({
  getParentRoute: () => environmentsRoute,
  path: '$environmentId',
  component: function EnvironmentEditRoute() {
    const { environmentId } = environmentEditRoute.useParams();
    return <EnvironmentEditPage key={environmentId} environmentId={environmentId} />;
  },
});

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
    agentsRoute.addChildren([
      agentListRoute,
      agentRoute.addChildren([agentDetailRoute, agentEditRoute, agentCompareRoute]),
    ]),
    environmentsRoute.addChildren([environmentListRoute, environmentNewRoute, environmentEditRoute]),
    capabilitiesRoute.addChildren([capabilityListRoute, skillRoute, mcpServerRoute]),
    knowledgeRoute,
    modelsRoute.addChildren([modelListRoute, priceBookRoute]),
    pendingRoute,
    usageRoute,
    settingsRoute.addChildren([settingsHomeRoute, secretsRoute]),
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
