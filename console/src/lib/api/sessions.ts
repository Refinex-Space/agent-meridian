/**
 * 会话相关的请求。类型全部来自生成的 v1.d.ts。
 * TODO(T21)：T21 引入 openapi-fetch 客户端（认证头、problem+json 错误、request_id）后，这里改为经它调用。
 */
import type {
  EventPage,
  Schemas,
  SendEventsRequest,
  SendEventsResponse,
  Session,
  SessionEvent,
} from './types';

export interface RequestOptions {
  /** 公开 API 的前缀，默认 `/v1`。 */
  baseUrl?: string;
  headers?: Record<string, string>;
  signal?: AbortSignal;
}

/** 请求失败：带 HTTP 状态与服务端返回的 problem+json（如果有）。 */
export class ApiError extends Error {
  readonly status: number;
  readonly problem: Schemas['Problem'] | null;

  constructor(status: number, problem: Schemas['Problem'] | null) {
    super(problem?.detail ?? problem?.title ?? `请求失败（HTTP ${status}）`);
    this.status = status;
    this.problem = problem;
  }
}

async function request<T>(path: string, options: RequestOptions, init: RequestInit = {}): Promise<T> {
  const response = await fetch(`${options.baseUrl ?? '/v1'}${path}`, {
    ...init,
    headers: { Accept: 'application/json', ...init.headers, ...options.headers },
    signal: options.signal ?? null,
  });
  if (!response.ok) {
    const problem = response.headers.get('Content-Type')?.includes('json')
      ? ((await response.json()) as Schemas['Problem'])
      : null;
    throw new ApiError(response.status, problem);
  }
  return (await response.json()) as T;
}

export function getSession(sessionId: string, options: RequestOptions = {}): Promise<Session> {
  return request<Session>(`/sessions/${encodeURIComponent(sessionId)}`, options);
}

export function getAgent(agentId: string, options: RequestOptions = {}): Promise<Schemas['Agent']> {
  return request<Schemas['Agent']>(`/agents/${encodeURIComponent(agentId)}`, options);
}

/** 按页读完会话的全部持久化事件（每页 100 条）。 */
export async function listAllEvents(
  sessionId: string,
  options: RequestOptions = {},
): Promise<SessionEvent[]> {
  const events: SessionEvent[] = [];
  let cursor: string | null = null;
  do {
    const query = new URLSearchParams({ limit: '100' });
    if (cursor) query.set('after', cursor);
    const page: EventPage = await request<EventPage>(
      `/sessions/${encodeURIComponent(sessionId)}/events?${query.toString()}`,
      options,
    );
    events.push(...page.data);
    cursor = page.has_more ? page.next_cursor : null;
  } while (cursor);
  return events;
}

export function sendEvents(
  sessionId: string,
  body: SendEventsRequest,
  options: RequestOptions = {},
): Promise<SendEventsResponse> {
  return request<SendEventsResponse>(`/sessions/${encodeURIComponent(sessionId)}/events`, options, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
}

/** 列表查询参数：值为 undefined 或空串的键不出现在 URL 中。 */
type Query = Record<string, string | number | undefined>;

function withQuery(path: string, query: Query): string {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query)) {
    if (value !== undefined && value !== '') params.set(key, String(value));
  }
  const text = params.toString();
  return text ? `${path}?${text}` : path;
}

/** 会话列表（游标分页），筛选条件见 `GET /v1/sessions`。 */
export function listSessions(
  query: {
    limit?: number;
    after?: string;
    agent_id?: string;
    status?: Schemas['SessionStatus'];
    end_user_id?: string;
    created_after?: string;
    created_before?: string;
  },
  options: RequestOptions = {},
): Promise<Schemas['SessionPage']> {
  return request<Schemas['SessionPage']>(withQuery('/sessions', query), options);
}

/** 本项目的智能体（游标分页）。 */
export function listAgents(
  query: { limit?: number; after?: string },
  options: RequestOptions = {},
): Promise<Schemas['AgentPage']> {
  return request<Schemas['AgentPage']>(withQuery('/agents', query), options);
}

/** 本项目的待处理动作（游标分页），筛选条件见 `GET /v1/pending-actions`。 */
export function listPendingActions(
  query: {
    limit?: number;
    after?: string;
    status?: Schemas['PendingActionStatus'];
    type?: Schemas['PendingActionType'];
    agent_id?: string;
    session_id?: string;
  },
  options: RequestOptions = {},
): Promise<Schemas['PendingActionPage']> {
  return request<Schemas['PendingActionPage']>(withQuery('/pending-actions', query), options);
}

/** 会话运行概况：运行中、等待回应的会话数，待处理动作数，按智能体的失败率与最近的失败轮次。 */
export function getSessionStats(
  query: { window?: Schemas['StatsWindow']; agent_id?: string },
  options: RequestOptions = {},
): Promise<Schemas['SessionStats']> {
  return request<Schemas['SessionStats']>(withQuery('/session-stats', query), options);
}
