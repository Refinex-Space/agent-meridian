/**
 * 项目级的公开 API（会话列表、智能体、待处理动作、运行概况与回应），供会话列表、待处理、概览原型使用。
 * 数据来自 fixtures 中以"现在"为基准生成的夹具；`live` 时之后的轮询会多出新的会话与待处理动作，演示"有 N 条更新"。
 */
import { delay, http, HttpResponse, type DefaultBodyType, type PathParams, type RequestHandler } from 'msw';

import type {
  EventOf,
  PendingAction,
  Schemas,
  SendEventsRequest,
  Session,
  SessionEvent,
} from '@/lib/api/types';

import { AGENT_SERVICE_QA, AGENT_STORE_PATROL, PROJECT_AGENTS } from '../fixtures/agents';
import { buildPendingActions } from '../fixtures/pending-actions';
import { buildProjectSessions } from '../fixtures/project-sessions';
import { buildSessionStats } from '../fixtures/session-stats';

export type ProjectMode = 'normal' | 'loading' | 'empty' | 'error';

export interface ProjectOptions {
  mode?: ProjectMode;
  /** 第二次及以后的列表请求多出新的会话与动作（实时更新）。 */
  live?: boolean;
  /** 首次请求的延迟（毫秒）。 */
  latency?: number;
  /** 长内容：一个名字很长的智能体，几个很长的终端用户标识。 */
  longContent?: boolean;
}

/** 长内容的写法：企业微信的终端用户标识、带括号说明的智能体名。 */
const LONG_AGENT_NAME = '门店巡检报告与整改跟踪（华东区试运行，含照片识别）';
const LONG_END_USER = (i: number) =>
  `wecom:ww8a3f2c91d0e7b6a4:store-manager-hangzhou-xixi-${String(i).padStart(3, '0')}`;

/** 控制台用户在原型中的标识（回应者）。 */
const CURRENT_SUBJECT = 'f3a9c2e1-7b4d-4e58-9a61-2c8d0e5b7f14';

/** 成功时是 T，失败时是 problem+json。 */
type OrProblem<T> = T | Schemas['Problem'];

function problem(status: number, code: string, title: string, detail: string, instance: string) {
  return HttpResponse.json<Schemas['Problem']>(
    {
      type: `urn:meridian:error:${code}`,
      title,
      status,
      detail,
      instance,
      code,
      request_id: 'req_01JCQB9D2F5H8K1M4P7R0T3W6Y',
    },
    { status, headers: { 'Content-Type': 'application/problem+json' } },
  );
}

/** 游标就是下一页第一条的下标。 */
function page<T>(
  items: readonly T[],
  url: URL,
): { data: T[]; has_more: boolean; next_cursor: string | null } {
  const limit = Number(url.searchParams.get('limit') ?? 50);
  const start = Number(url.searchParams.get('after') ?? 0);
  const data = items.slice(start, start + limit);
  const more = start + limit < items.length;
  return { data, has_more: more, next_cursor: more ? String(start + limit) : null };
}

/** 新到的会话（实时演示）：两个运行中，一个刚停在等待处理。 */
function arrivals(now: number): Session[] {
  const base = buildProjectSessions(now, 3, 97);
  return base.map((s, i) => ({
    ...s,
    id: s.id.replace('ses_01JC', 'ses_01JD'),
    agent_id: i === 2 ? AGENT_STORE_PATROL.id : AGENT_SERVICE_QA.id,
    status: i === 2 ? 'requires_action' : 'running',
    created_at: new Date(now - (i + 1) * 9_000).toISOString(),
    updated_at: new Date(now - i * 4_000).toISOString(),
    terminated_at: null,
  }));
}

export function projectHandlers(options: ProjectOptions = {}): RequestHandler[] {
  const { mode = 'normal', live = false, latency = 260, longContent = false } = options;
  const start = Date.now();
  const built = mode === 'empty' ? [] : buildProjectSessions(start);
  const sessions = longContent
    ? built.map((s, i) =>
        s.agent_id === AGENT_STORE_PATROL.id ? { ...s, end_user_id: LONG_END_USER(i) } : s,
      )
    : built;
  const agents = longContent
    ? PROJECT_AGENTS.map((a) => (a.id === AGENT_STORE_PATROL.id ? { ...a, name: LONG_AGENT_NAME } : a))
    : PROJECT_AGENTS;
  const actions = mode === 'empty' ? [] : buildPendingActions(start);
  let sessionReads = 0;

  const wait = async () => {
    if (mode === 'loading') await delay('infinite');
    await delay(latency);
  };

  const failed = (instance: string) =>
    problem(
      503,
      'internal.unavailable',
      'Service Unavailable',
      '运行时节点正在重启，暂时无法读取。',
      instance,
    );

  return [
    http.get('/v1/agents', async ({ request }) => {
      await wait();
      return HttpResponse.json<Schemas['AgentPage']>(page(agents, new URL(request.url)));
    }),

    http.get<PathParams, DefaultBodyType, OrProblem<Schemas['SessionPage']>>(
      '/v1/sessions',
      async ({ request }) => {
        await wait();
        if (mode === 'error') return failed('/v1/sessions');
        sessionReads += 1;
        const url = new URL(request.url);
        const all = live && sessionReads > 1 ? [...arrivals(Date.now()), ...sessions] : sessions;
        const agent = url.searchParams.get('agent_id');
        const status = url.searchParams.get('status');
        const endUser = url.searchParams.get('end_user_id');
        const after = url.searchParams.get('created_after');
        const filtered = all.filter(
          (s) =>
            (!agent || s.agent_id === agent) &&
            (!status || s.status === status) &&
            (!endUser || s.end_user_id === endUser) &&
            (!after || Date.parse(s.created_at) >= Date.parse(after)),
        );
        return HttpResponse.json<Schemas['SessionPage']>(page(filtered, url));
      },
    ),

    http.get<PathParams, DefaultBodyType, OrProblem<Schemas['PendingActionPage']>>(
      '/v1/pending-actions',
      async ({ request }) => {
        await wait();
        if (mode === 'error') return failed('/v1/pending-actions');
        const url = new URL(request.url);
        const status = url.searchParams.get('status');
        const type = url.searchParams.get('type');
        const agent = url.searchParams.get('agent_id');
        const session = url.searchParams.get('session_id');
        const filtered = actions.filter(
          (a) =>
            (!status || a.status === status) &&
            (!type || a.type === type) &&
            (!agent || a.agent_id === agent) &&
            (!session || a.session_id === session),
        );
        return HttpResponse.json<Schemas['PendingActionPage']>(page(filtered, url));
      },
    ),

    http.get<PathParams, DefaultBodyType, OrProblem<Schemas['SessionStats']>>(
      '/v1/session-stats',
      async ({ request }) => {
        await wait();
        if (mode === 'error') return failed('/v1/session-stats');
        const window = (new URL(request.url).searchParams.get('window') ?? '24h') as Schemas['StatsWindow'];
        const stats = buildSessionStats(window, Date.now(), sessions);
        // 新项目：还没有任何轮次与待处理动作。
        return HttpResponse.json(
          mode === 'empty' ? { ...stats, agents: [], recent_errors: [], pending_actions: 0 } : stats,
        );
      },
    ),

    // 回应待处理动作：工具确认与恢复确认。动作就地变为已回应，下一次列表请求就看不到它了。
    http.post<PathParams, DefaultBodyType, OrProblem<Schemas['SendEventsResponse']>>(
      '/v1/sessions/:sessionId/events',
      async ({ request, params }) => {
        await delay(500);
        const body = (await request.json()) as SendEventsRequest;
        const sessionId = String(params['sessionId']);
        const now = new Date().toISOString();
        const data: SessionEvent[] = [];
        for (const input of body.events) {
          if (input.type !== 'user.tool_confirmation' && input.type !== 'user.recovery_confirmation')
            continue;
          const index = actions.findIndex((a) => a.id === input.action_id);
          const action = actions[index];
          if (!action || action.status !== 'pending') {
            return problem(
              409,
              'action.not_pending',
              'Conflict',
              '这个动作已经被回应或已超时。',
              `/v1/sessions/${sessionId}/events`,
            );
          }
          const resolved: PendingAction = {
            ...action,
            status: 'resolved',
            resolved_at: now,
            decided_by: CURRENT_SUBJECT,
            conclusion: {
              origin: 'caller',
              approved: input.type === 'user.tool_confirmation' ? input.approved : null,
              deny_reason: null,
              remember_scope:
                input.type === 'user.tool_confirmation' ? (input.remember_scope ?? 'none') : null,
              remember_rule: input.type === 'user.tool_confirmation' ? (input.remember_rule ?? null) : null,
              decision: input.type === 'user.recovery_confirmation' ? input.decision : null,
              error: null,
            },
          };
          actions.splice(index, 1, resolved);
          const envelope = {
            id: `evt_${action.id.slice(4)}`,
            seq: 62,
            session_id: sessionId,
            thread_id: action.thread_id,
            turn_id: null,
            created_at: now,
          };
          if (input.type === 'user.tool_confirmation') {
            const event: EventOf<'user.tool_confirmation'> = {
              ...envelope,
              type: input.type,
              data: {
                action_id: input.action_id,
                approved: input.approved,
                updated_input: null,
                deny_reason: null,
                remember_scope: input.remember_scope ?? 'none',
                remember_rule: input.remember_rule ?? null,
              },
            };
            data.push(event);
          } else {
            const event: EventOf<'user.recovery_confirmation'> = {
              ...envelope,
              type: input.type,
              data: { action_id: input.action_id, decision: input.decision },
            };
            data.push(event);
          }
        }
        return HttpResponse.json<Schemas['SendEventsResponse']>({ data });
      },
    ),
  ];
}
