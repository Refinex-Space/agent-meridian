/**
 * 测试与故事里的路由：用到 TanStack Router（Link、搜索参数、导航）的组件要在路由之内渲染。任何路径都渲染给定的内容；
 * 搜索参数的格式与正式应用相同（`lib/search.ts`）。
 */
import type { Decorator } from '@storybook/react-vite';
import {
  createBrowserHistory,
  createMemoryHistory,
  createRootRoute,
  createRoute,
  createRouter,
  Outlet,
  RouterProvider,
  type AnyRouter,
  type RouterHistory,
} from '@tanstack/react-router';
import { render, type RenderResult } from '@testing-library/react';
import { createContext, use, type ReactNode } from 'react';

import { parseSearch, stringifySearch } from '@/lib/search';

/** 根路由渲染的内容：经 Context 传入，换内容时不必重建路由。 */
const Content = createContext<ReactNode>(null);

function Root() {
  return (
    <>
      {use(Content)}
      <Outlet />
    </>
  );
}

/** 根路由加一个匹配任何路径的子路由：任何地址都渲染 Content。 */
function routerFor(history: RouterHistory): AnyRouter {
  const rootRoute = createRootRoute({ component: Root });
  const anyPath = createRoute({ getParentRoute: () => rootRoute, path: '$', component: () => null });
  return createRouter({
    routeTree: rootRoute.addChildren([anyPath]),
    history,
    parseSearch,
    stringifySearch,
  });
}

export interface RouterRenderResult extends Omit<RenderResult, 'rerender'> {
  history: RouterHistory;
  /** 换成新的内容重新渲染，路由与地址保持不变。 */
  rerender: (ui: ReactNode) => void;
}

/**
 * Vitest：在路由中渲染。默认用浏览器历史（读写 jsdom 的 `window.location`，与地址栏行为一致）；给 `url` 时用内存历史。
 */
export async function renderWithRouter(
  ui: ReactNode,
  { url }: { url?: string } = {},
): Promise<RouterRenderResult> {
  const history = url ? createMemoryHistory({ initialEntries: [url] }) : createBrowserHistory();
  const router = routerFor(history);
  await router.load();
  const tree = (content: ReactNode) => (
    <Content value={content}>
      <RouterProvider router={router} />
    </Content>
  );
  const result = render(tree(ui));
  return { ...result, history, rerender: (next) => result.rerender(tree(next)) };
}

/** Storybook：故事渲染在内存路由中（`parameters.route` 给初始地址，默认 `/`），Link 与导航不离开故事。 */
export const withRouter: Decorator = (Story, context) => {
  const url = (context.parameters['route'] as string | undefined) ?? '/';
  const router = routerFor(createMemoryHistory({ initialEntries: [url] }));
  return (
    <Content value={<Story />}>
      <RouterProvider router={router} />
    </Content>
  );
};
