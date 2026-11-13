import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import type * as ClientModuleNs from './client';
import type * as ConnectionModuleNs from './connection';

type ClientModule = typeof ClientModuleNs;
type ConnectionModule = typeof ConnectionModuleNs;

const PROBLEM = {
  type: 'urn:meridian:error:session.not_found',
  title: '会话不存在',
  status: 404,
  detail: '会话 ses_x 不存在或已删除。',
  instance: '/v1/sessions/ses_x',
  code: 'session.not_found',
  request_id: 'req_01JC8Z4V7Q',
};

const json = (body: unknown, status = 200, headers: Record<string, string> = {}) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json', ...headers },
  });

/** openapi-fetch 在创建客户端时取 fetch：先替换全局 fetch，再重新加载模块。 */
async function load(
  fetchMock: typeof fetch,
): Promise<{ client: ClientModule; connection: ConnectionModule }> {
  vi.stubGlobal('fetch', fetchMock);
  vi.resetModules();
  return { client: await import('./client'), connection: await import('./connection') };
}

function lastRequest(mock: ReturnType<typeof vi.fn>): Request {
  return mock.mock.calls.at(-1)?.[0] as Request;
}

describe('API 客户端', () => {
  let fetchMock: ReturnType<typeof vi.fn<typeof fetch>>;

  beforeEach(() => {
    fetchMock = vi.fn<typeof fetch>();
  });
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('管理 API 带 OIDC 令牌，不带项目头', async () => {
    fetchMock.mockResolvedValue(json({ subject: 'u', email: null, platform_admin: false, memberships: [] }));
    const { client } = await load(fetchMock);
    client.setAuthHandlers({ getAccessToken: () => Promise.resolve('tok_1'), onUnauthorized: vi.fn() });
    const me = await client.unwrap(client.adminApi().GET('/admin/v1/me'));
    expect(me.subject).toBe('u');
    const request = lastRequest(fetchMock);
    expect(request.url).toBe(`${window.location.origin}/admin/v1/me`);
    expect(request.headers.get('Authorization')).toBe('Bearer tok_1');
    expect(request.headers.has('Meridian-Project')).toBe(false);
  });

  it('公开 API 带项目头，同一项目复用同一个客户端；没有登记令牌来源时不带认证头', async () => {
    fetchMock.mockImplementation(() =>
      Promise.resolve(json({ data: [], has_more: false, next_cursor: null })),
    );
    const { client } = await load(fetchMock);
    expect(client.v1Api('prj_1')).toBe(client.v1Api('prj_1'));
    await client.unwrap(client.v1Api('prj_1').GET('/v1/agents'));
    const request = lastRequest(fetchMock);
    expect(request.headers.get('Meridian-Project')).toBe('prj_1');
    expect(request.headers.has('Authorization')).toBe(false);
  });

  it('problem+json 映射为 ApiError，带状态、原因与请求 ID；401 交给认证模块', async () => {
    const onUnauthorized = vi.fn();
    fetchMock
      .mockResolvedValueOnce(json(PROBLEM, 404))
      .mockResolvedValueOnce(json({ ...PROBLEM, status: 401 }, 401));
    const { client } = await load(fetchMock);
    const { ApiError } = await import('./errors');
    client.setAuthHandlers({ getAccessToken: () => Promise.resolve('tok'), onUnauthorized });
    const api = client.v1Api('prj_1');
    const request = () =>
      client.unwrap(api.GET('/v1/sessions/{session_id}', { params: { path: { session_id: 'ses_x' } } }));

    const notFound = await request().catch((e: unknown) => e);
    expect(notFound).toBeInstanceOf(ApiError);
    expect(notFound).toMatchObject({ status: 404, requestId: 'req_01JC8Z4V7Q', message: PROBLEM.detail });
    expect(onUnauthorized).not.toHaveBeenCalled();

    await expect(request()).rejects.toMatchObject({ status: 401 });
    expect(onUnauthorized).toHaveBeenCalledTimes(1);
  });

  it('响应不是 problem+json 时，请求 ID 取 X-Request-Id 响应头', async () => {
    fetchMock.mockResolvedValue(
      new Response('Bad Gateway', { status: 502, headers: { 'X-Request-Id': 'req_gateway' } }),
    );
    const { client } = await load(fetchMock);
    await expect(client.unwrap(client.adminApi().GET('/admin/v1/me'))).rejects.toMatchObject({
      status: 502,
      problem: null,
      requestId: 'req_gateway',
    });
  });

  it('网络不可达或网关不可用时记为与服务端断开，之后任一请求成功即恢复', async () => {
    fetchMock
      .mockRejectedValueOnce(new TypeError('Failed to fetch'))
      .mockResolvedValueOnce(json({ ...PROBLEM, status: 503 }, 503))
      .mockResolvedValueOnce(json(PROBLEM, 404))
      .mockResolvedValueOnce(json({ subject: 'u', email: null, platform_admin: false, memberships: [] }));
    const { client, connection } = await load(fetchMock);
    const states: string[] = [];
    connection.subscribeConnection(() => states.push(connection.getConnectionState()));
    const me = () => client.unwrap(client.adminApi().GET('/admin/v1/me')).catch(() => null);

    await me();
    expect(connection.getConnectionState()).toBe('offline');
    await me();
    expect(connection.getConnectionState()).toBe('offline');
    await me(); // 404：服务端可达
    expect(connection.getConnectionState()).toBe('online');
    await me();
    expect(states).toEqual(['offline', 'online']);
  });

  it('authorizedFetch 给 openapi-fetch 之外的请求（SSE）加上认证与项目头', async () => {
    fetchMock.mockResolvedValue(new Response(''));
    const { client } = await load(fetchMock);
    client.setAuthHandlers({ getAccessToken: () => Promise.resolve('tok_2'), onUnauthorized: vi.fn() });
    await client.authorizedFetch('prj_9')('/v1/sessions/ses_1/stream', {
      headers: { Accept: 'text/event-stream' },
    });
    const init = fetchMock.mock.calls[0]?.[1];
    const headers = new Headers(init?.headers);
    expect(headers.get('Authorization')).toBe('Bearer tok_2');
    expect(headers.get('Meridian-Project')).toBe('prj_9');
    expect(headers.get('Accept')).toBe('text/event-stream');
  });
});
