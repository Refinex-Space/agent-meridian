/**
 * API 客户端：openapi-fetch 按生成的类型调用公开 API（`v1.d.ts`）与管理 API（`admin.d.ts`），路径都是规范中的绝对路径
 * （`/v1/…`、`/admin/v1/…`），由同源的入口按路径路由到 runtime、control-plane、knowledge（ADR-0055）。
 *
 * 每个请求注入 `Authorization: Bearer <OIDC access token>`；公开 API 另带 `Meridian-Project`（ADR-0044）。令牌由认证模块
 * 在启动时经 `setAuthHandlers` 提供，没有登记时（Storybook、组件测试）不带认证头。
 */
import createClient, { type Client, type Middleware } from 'openapi-fetch';

import type { paths as AdminPaths } from './admin';
import { reportNetworkFailure, reportResponse } from './connection';
import { ApiError } from './errors';
import type { paths as V1Paths } from './v1';

export type V1Client = Client<V1Paths>;
export type AdminClient = Client<AdminPaths>;

export interface AuthHandlers {
  /** 当前有效的 access token；快过期时先续期再返回。没有登录时为 null。 */
  getAccessToken: () => Promise<string | null>;
  /** 服务端返回 401：令牌已失效（被吊销或会话结束），由认证模块重新登录。 */
  onUnauthorized: () => void;
}

let auth: AuthHandlers | null = null;

/** 认证模块启动时登记令牌的来源；传 null 撤销（退出登录、测试）。 */
export function setAuthHandlers(handlers: AuthHandlers | null): void {
  auth = handlers;
}

/** 给请求头加上认证与项目头；`headers` 被就地修改。 */
async function applyHeaders(headers: Headers, projectId: string | undefined): Promise<void> {
  const token = auth ? await auth.getAccessToken() : null;
  if (token) headers.set('Authorization', `Bearer ${token}`);
  if (projectId) headers.set('Meridian-Project', projectId);
}

function observe(status: number): void {
  reportResponse(status);
  if (status === 401) auth?.onUnauthorized();
}

function middleware(projectId: string | undefined): Middleware {
  return {
    async onRequest({ request }) {
      await applyHeaders(request.headers, projectId);
      return request;
    },
    onResponse({ response }) {
      observe(response.status);
    },
    onError({ error }) {
      if (error instanceof TypeError) reportNetworkFailure();
    },
  };
}

/**
 * 带认证与项目头的 fetch，给 openapi-fetch 之外的请求用（SSE 的流式读取、文件下载）。
 */
export function authorizedFetch(projectId?: string): typeof fetch {
  return async (input, init) => {
    const headers = new Headers(init?.headers);
    await applyHeaders(headers, projectId);
    try {
      const response = await fetch(input, { ...init, headers });
      observe(response.status);
      return response;
    } catch (error) {
      if (error instanceof TypeError) reportNetworkFailure();
      throw error;
    }
  };
}

/** 同源：浏览器中是页面的源；jsdom 中是测试页面的源（Node 的 Request 不接受相对地址）。 */
const baseUrl = globalThis.location.origin;

const adminClient: AdminClient = createClient<AdminPaths>({ baseUrl });
adminClient.use(middleware(undefined));

/** 管理 API（只接受控制台用户的 OIDC 令牌，不带项目头：租户与项目在路径里）。 */
export function adminApi(): AdminClient {
  return adminClient;
}

const projectClients = new Map<string, V1Client>();
let unscopedClient: V1Client | null = null;

/**
 * 公开 API 的客户端，所有请求带 `Meridian-Project: <projectId>`。同一项目复用同一个实例。不给项目时不带项目头
 * （只用于 Storybook 与组件测试中由 MSW 应答的请求）。
 */
export function v1Api(projectId?: string): V1Client {
  if (!projectId) {
    if (!unscopedClient) {
      unscopedClient = createClient<V1Paths>({ baseUrl });
      unscopedClient.use(middleware(undefined));
    }
    return unscopedClient;
  }
  let client = projectClients.get(projectId);
  if (!client) {
    client = createClient<V1Paths>({ baseUrl });
    client.use(middleware(projectId));
    projectClients.set(projectId, client);
  }
  return client;
}

/** openapi-fetch 的结果：成功时有 `data`，失败时有 `error`。 */
interface FetchResult {
  data?: unknown;
  error?: unknown;
  response: Response;
}

/**
 * 取出成功响应的数据；失败时抛出 `ApiError`（带 problem+json 与 `request_id`），供 TanStack Query 的错误与重试使用。
 * 没有响应体的操作（204）得到 undefined。
 *
 * @example
 * const session = await unwrap(api.GET('/v1/sessions/{session_id}', { params: { path: { session_id } }, signal }));
 */
export async function unwrap<R extends FetchResult>(
  pending: Promise<R>,
): Promise<Exclude<R['data'], undefined>> {
  const result = await pending;
  if (result.error !== undefined || !result.response.ok) {
    throw ApiError.from(result.response, result.error);
  }
  // 成功分支的 data 就是规范中的响应体；204 时为 undefined。
  return result.data as Exclude<R['data'], undefined>;
}
