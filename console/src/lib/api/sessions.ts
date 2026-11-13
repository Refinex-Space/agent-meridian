/**
 * 会话相关的请求，经 openapi-fetch 客户端调用（认证头、项目头、problem+json 错误）。类型全部来自生成的 v1.d.ts。
 */
import { unwrap, v1Api, type V1Client } from './client';
import type {
  EventPage,
  Schemas,
  SendEventsRequest,
  SendEventsResponse,
  Session,
  SessionEvent,
} from './types';

export interface RequestOptions {
  /** 公开 API 的客户端；页面传当前项目的 `v1Api(projectId)`，不传时不带项目头（MSW 应答的原型与测试）。 */
  client?: V1Client;
  signal?: AbortSignal;
}

function api(options: RequestOptions): V1Client {
  return options.client ?? v1Api();
}

export function getSession(sessionId: string, options: RequestOptions = {}): Promise<Session> {
  return unwrap(
    api(options).GET('/v1/sessions/{session_id}', {
      params: { path: { session_id: sessionId } },
      signal: options.signal,
    }),
  );
}

export function getAgent(agentId: string, options: RequestOptions = {}): Promise<Schemas['Agent']> {
  return unwrap(
    api(options).GET('/v1/agents/{agent_id}', {
      params: { path: { agent_id: agentId } },
      signal: options.signal,
    }),
  );
}

/** 按页读完会话的全部持久化事件（每页 100 条）。 */
export async function listAllEvents(
  sessionId: string,
  options: RequestOptions = {},
): Promise<SessionEvent[]> {
  const events: SessionEvent[] = [];
  let cursor: string | null = null;
  do {
    const page: EventPage = await unwrap(
      api(options).GET('/v1/sessions/{session_id}/events', {
        params: {
          path: { session_id: sessionId },
          query: { limit: 100, ...(cursor ? { after: cursor } : {}) },
        },
        signal: options.signal,
      }),
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
  return unwrap(
    api(options).POST('/v1/sessions/{session_id}/events', {
      params: { path: { session_id: sessionId } },
      body,
      signal: options.signal,
    }),
  );
}

/** 去掉值为 undefined 或空串的查询参数，不让空的筛选条件出现在 URL 中。 */
function compact<Q extends object>(query: Q): Q {
  return Object.fromEntries(
    Object.entries(query).filter(([, value]) => value !== undefined && value !== ''),
  ) as Q;
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
  return unwrap(
    api(options).GET('/v1/sessions', { params: { query: compact(query) }, signal: options.signal }),
  );
}

/** 本项目的智能体（游标分页）。 */
export function listAgents(
  query: { limit?: number; after?: string },
  options: RequestOptions = {},
): Promise<Schemas['AgentPage']> {
  return unwrap(
    api(options).GET('/v1/agents', { params: { query: compact(query) }, signal: options.signal }),
  );
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
  return unwrap(
    api(options).GET('/v1/pending-actions', { params: { query: compact(query) }, signal: options.signal }),
  );
}

/** 会话运行概况：运行中、等待回应的会话数，待处理动作数，按智能体的失败率与最近的失败轮次。 */
export function getSessionStats(
  query: { window?: Schemas['StatsWindow']; agent_id?: string },
  options: RequestOptions = {},
): Promise<Schemas['SessionStats']> {
  return unwrap(
    api(options).GET('/v1/session-stats', { params: { query: compact(query) }, signal: options.signal }),
  );
}
