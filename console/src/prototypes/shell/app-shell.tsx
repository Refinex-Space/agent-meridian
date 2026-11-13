import { createMemoryHistory, createRootRoute, createRouter, RouterProvider } from '@tanstack/react-router';
import { createContext, use, useState, type ReactNode } from 'react';

import { AppShell as Shell, type ShellCrumb } from '@/app/shell/app-shell';
import type { NavKey } from '@/app/shell/nav';
import type { TenantProjects } from '@/lib/access';
import type { ThemePreference } from '@/lib/theme';

import { CURRENT_PROJECT_ID, CURRENT_USER, TENANTS } from '../fixtures/identity';

export type { ShellCrumb };

export interface AppShellProps {
  /** 当前一级导航。 */
  active: NavKey;
  /** 待处理动作数，显示在"待处理"上。 */
  pendingCount?: number;
  breadcrumb: readonly ShellCrumb[];
  /** 顶栏下方的横幅（预算告警、与服务端断开）。 */
  banner?: ReactNode;
  /** 导航的初始折叠状态；不给时读本地记录。 */
  defaultCollapsed?: boolean;
  children: ReactNode;
}

const PROJECTS: TenantProjects[] = TENANTS.map((t) => ({
  tenant_id: t.tenantId,
  tenant_name: t.tenantName,
  projects: t.projects.map((p) => ({ ...p, tenant_id: t.tenantId, tenant_name: t.tenantName })),
}));

/** 外壳在路由之内渲染（导航与面包屑是路由链接）：内存路由的根组件读出这一次要渲染的外壳。 */
const ShellElement = createContext<ReactNode>(null);

function StoryShell() {
  return use(ShellElement);
}

function useStoryRouter() {
  const [router] = useState(() =>
    createRouter({
      routeTree: createRootRoute({ component: StoryShell }),
      history: createMemoryHistory({ initialEntries: [`/projects/${CURRENT_PROJECT_ID}/overview`] }),
    }),
  );
  return router;
}

/**
 * 原型页面的外壳：正式的应用外壳（`app/shell/app-shell.tsx`）加上原型项目"华东经营分析"的身份夹具，渲染在内存路由中。
 * 原型故事经它展示页面在外壳中的样子；页面任务用正式路由替换原型后随之删除。
 */
export function AppShell({
  active,
  pendingCount,
  breadcrumb,
  banner,
  defaultCollapsed,
  children,
}: AppShellProps) {
  const router = useStoryRouter();
  const [projectId, setProjectId] = useState(CURRENT_PROJECT_ID);
  const [theme, setTheme] = useState<ThemePreference>('system');
  const name = PROJECTS.flatMap((t) => t.projects).find((p) => p.id === projectId)?.name ?? '';
  const shell = (
    <Shell
      active={active}
      project={{ id: projectId, name }}
      projects={PROJECTS}
      onSwitchProject={setProjectId}
      user={{ name: CURRENT_USER.displayName, email: CURRENT_USER.email, roles: CURRENT_USER.role }}
      theme={theme}
      onThemeChange={setTheme}
      onLogout={() => undefined}
      pendingCount={pendingCount}
      breadcrumb={breadcrumb}
      banner={banner}
      defaultCollapsed={defaultCollapsed}
      onNavigate={() => undefined}
    >
      {children}
    </Shell>
  );
  return (
    <ShellElement value={shell}>
      <RouterProvider router={router} />
    </ShellElement>
  );
}
