/**
 * 测试与故事里的路由：用到 TanStack Router（Link、搜索参数、导航）的组件要在路由之内渲染。搜索参数的格式与正式应用相同
 * （`lib/search.ts`）。
 */
import type { Decorator } from '@storybook/react-vite';
import {
  createBrowserHistory,
  createMemoryHistory,
  createRootRoute,
  createRouter,
  Outlet,
  RouterProvider,
  type AnyRouter,
  type RouterHistory,
} from '@tanstack/react-router';
import { render, type RenderResult } from '@testing-library/react';
import type { ReactNode } from 'react';

import { parseSearch, stringifySearch } from '@/lib/search';

/** 只有一个根路由的路由器：任何路径都渲染 `ui`。 */
function routerFor(ui: () => ReactNode, history: RouterHistory): AnyRouter {
  const rootRoute = createRootRoute({
    component: () => (
      <>
        {ui()}
        <Outlet />
      </>
    ),
  });
  return createRouter({
    routeTree: rootRoute,
    history,
    parseSearch,
    stringifySearch,
    defaultPendingMinMs: 0,
  });
}

/**
 * Vitest：在路由中渲染。默认用浏览器历史（读写 jsdom 的 `window.location`，与地址栏行为一致）；给 `url` 时用内存历史。
 */
export async function renderWithRouter(
  ui: ReactNode,
  { url }: { url?: string } = {},
): Promise<RenderResult & { history: RouterHistory }> {
  const history = url ? createMemoryHistory({ initialEntries: [url] }) : createBrowserHistory();
  const router = routerFor(() => ui, history);
  await router.load();
  return { ...render(<RouterProvider router={router} />), history };
}

/** Storybook：故事渲染在内存路由中（`parameters.route` 给初始地址，默认 `/`），Link 与导航不离开故事。 */
export const withRouter: Decorator = (Story, context) => {
  const url = (context.parameters['route'] as string | undefined) ?? '/';
  const router = routerFor(() => <Story />, createMemoryHistory({ initialEntries: [url] }));
  return <RouterProvider router={router} />;
};
