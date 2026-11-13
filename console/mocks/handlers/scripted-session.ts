/**
 * 用 MSW 模拟一个会话的公开 API：会话与智能体资源、分页的事件列表、SSE 事件流、发送回应事件。
 * 流按架构设计「SSE 协议」：先发 `:open`，补发 `Last-Event-ID` 之后的事件，再实时推送脚本中的消息；每 15 秒一个 ping。
 */
import { delay, http, HttpResponse, type RequestHandler } from 'msw';

import type { Schemas, SendEventsRequest, Session, SessionEvent, StreamOnlyEvent } from '@/lib/api/types';

import type { StreamStep } from '../fixtures/q3-session';

export interface ScriptedSessionOptions {
  session: Session;
  agent: Schemas['Agent'];
  history: readonly SessionEvent[];
  /** 第一次连上事件流后依次推送的消息。 */
  script?: readonly StreamStep[];
  /** 回应待处理动作之后推送的消息；第一条必须是回应事件本身。 */
  onRespond?: (actionId: string, approved: boolean) => readonly StreamStep[];
  /** 推送的事件把时间改为推送的时刻（实时演示）；否则保留夹具中的时间。 */
  liveTimestamps?: boolean;
  /** 首次读取会话的延迟（毫秒），`infinite` 表示一直加载（骨架演示）。 */
  loadDelay?: number | 'infinite';
  /** 推送的速度倍率，1 为原速。 */
  speed?: number;
}

type Message = { event: SessionEvent } | { streamOnly: StreamOnlyEvent };
type Listener = (message: Message) => void;

const STATUS: Partial<Record<SessionEvent['type'], Session['status']>> = {
  'session.status_running': 'running',
  'session.status_idle': 'idle',
  'session.status_requires_action': 'requires_action',
  'session.status_rescheduled': 'rescheduled',
  'session.status_terminated': 'terminated',
};

class ScriptedSession {
  readonly events: SessionEvent[];
  readonly listeners = new Set<Listener>();
  status: Session['status'];
  private started = false;
  private readonly options: ScriptedSessionOptions;

  constructor(options: ScriptedSessionOptions) {
    this.options = options;
    this.events = [...options.history];
    this.status = options.session.status;
  }

  start(): void {
    if (this.started || !this.options.script) return;
    this.started = true;
    this.play(this.options.script);
  }

  /** 依次推送消息；每条按自己的 delay 等待。 */
  play(steps: readonly StreamStep[]): void {
    const speed = this.options.speed ?? 1;
    let index = 0;
    const next = () => {
      const step = steps[index];
      if (!step) return;
      index += 1;
      window.setTimeout(() => {
        this.emit(step);
        next();
      }, step.delay / speed);
    };
    next();
  }

  emit(step: StreamStep): void {
    const now = new Date().toISOString();
    const live = this.options.liveTimestamps ?? true;
    if ('event' in step) {
      const event = live ? { ...step.event, created_at: now } : step.event;
      this.events.push(event);
      this.status = STATUS[event.type] ?? this.status;
      for (const listener of this.listeners) listener({ event });
    } else {
      const streamOnly = { ...step.streamOnly, created_at: now };
      for (const listener of this.listeners) listener({ streamOnly });
    }
  }

  pendingActionIds(): Set<string> {
    const last = this.events.findLast((e) => e.type.startsWith('session.status_'));
    if (last?.type !== 'session.status_requires_action') return new Set();
    const responded = new Set(
      this.events.flatMap((e) =>
        e.type === 'user.tool_confirmation' ||
        e.type === 'user.custom_tool_result' ||
        e.type === 'user.recovery_confirmation'
          ? [e.data.action_id]
          : [],
      ),
    );
    return new Set(last.data.actions.map((a) => a.id).filter((id) => !responded.has(id)));
  }
}

function sseFrame(type: string, id: number | null, data: unknown): string {
  return `event: ${type}\n${id === null ? '' : `id: ${id}\n`}data: ${JSON.stringify(data)}\n\n`;
}

function problem(
  status: number,
  code: string,
  title: string,
  detail: string,
  instance: string,
): Schemas['Problem'] {
  return {
    type: `urn:meridian:error:${code}`,
    title,
    status,
    detail,
    instance,
    code,
    request_id: `req_${Math.random().toString(36).slice(2, 14)}`,
  };
}

/** 一个会话的全部模拟接口。 */
export function scriptedSessionHandlers(options: ScriptedSessionOptions): RequestHandler[] {
  const server = new ScriptedSession(options);
  const sessionPath = `/v1/sessions/${options.session.id}`;

  return [
    http.get(sessionPath, async () => {
      if (options.loadDelay !== undefined) await delay(options.loadDelay);
      const last = server.events.at(-1);
      return HttpResponse.json<Session>({
        ...options.session,
        status: server.status,
        last_seq: last?.seq ?? 0,
        updated_at: last?.created_at ?? options.session.updated_at,
      });
    }),

    http.get(`/v1/agents/${options.agent.id}`, () => HttpResponse.json(options.agent)),

    http.get(`${sessionPath}/events`, async ({ request }) => {
      if (options.loadDelay !== undefined) await delay(options.loadDelay);
      const url = new URL(request.url);
      const limit = Math.min(100, Number(url.searchParams.get('limit') ?? 20));
      const after = Number(url.searchParams.get('after') ?? 0);
      const rest = server.events.filter((e) => e.seq > after);
      const data = rest.slice(0, limit);
      const hasMore = rest.length > limit;
      return HttpResponse.json<Schemas['EventPage']>({
        data,
        has_more: hasMore,
        next_cursor: hasMore ? String(data.at(-1)?.seq ?? after) : null,
      });
    }),

    http.get(`${sessionPath}/stream`, ({ request }) => {
      const lastId = Number(request.headers.get('Last-Event-ID') ?? 0);
      const encoder = new TextEncoder();
      let cleanup: () => void = () => undefined;
      const body = new ReadableStream<Uint8Array>({
        start(controller) {
          const send = (text: string) => {
            try {
              controller.enqueue(encoder.encode(text));
            } catch {
              cleanup();
            }
          };
          send(':open\n\n');
          for (const event of server.events)
            if (event.seq > lastId) send(sseFrame(event.type, event.seq, event));
          const listener: Listener = (message) => {
            if ('event' in message) {
              send(sseFrame(message.event.type, message.event.seq, message.event));
              if (message.event.type === 'session.status_terminated') {
                cleanup();
                controller.close();
              }
            } else {
              send(sseFrame(message.streamOnly.type, null, message.streamOnly));
            }
          };
          server.listeners.add(listener);
          const ping = window.setInterval(() => {
            const event: StreamOnlyEvent = {
              id: null,
              seq: null,
              session_id: options.session.id,
              thread_id: null,
              turn_id: null,
              type: 'ping',
              created_at: new Date().toISOString(),
              data: {},
            };
            send(sseFrame('ping', null, event));
          }, 15_000);
          cleanup = () => {
            server.listeners.delete(listener);
            window.clearInterval(ping);
          };
          request.signal.addEventListener('abort', () => cleanup());
          server.start();
        },
        cancel() {
          cleanup();
        },
      });
      return new HttpResponse(body, {
        headers: { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' },
      });
    }),

    http.post(`${sessionPath}/events`, async ({ request }) => {
      const body = (await request.json()) as SendEventsRequest;
      const input = body.events[0];
      if (input?.type !== 'user.tool_confirmation' || !options.onRespond) {
        return HttpResponse.json(
          problem(
            400,
            'request.validation_failed',
            '请求不合法',
            '这个会话只接受对待处理动作的回应。',
            `${sessionPath}/events`,
          ),
          { status: 400, headers: { 'Content-Type': 'application/problem+json' } },
        );
      }
      if (!server.pendingActionIds().has(input.action_id)) {
        return HttpResponse.json(
          problem(
            409,
            'event.action_not_pending',
            '动作已被处理',
            '这个动作已经被回应或已超时，刷新后查看最新状态。',
            `${sessionPath}/events`,
          ),
          { status: 409, headers: { 'Content-Type': 'application/problem+json' } },
        );
      }
      await delay(500);
      const [first, ...rest] = options.onRespond(input.action_id, input.approved);
      if (!first || !('event' in first) || first.event.type !== 'user.tool_confirmation') {
        throw new Error('onRespond 的第一条消息必须是 user.tool_confirmation 事件');
      }
      const response = {
        ...first.event,
        data: {
          ...first.event.data,
          approved: input.approved,
          deny_reason: input.deny_reason ?? null,
          remember_scope: input.remember_scope ?? 'none',
          remember_rule: input.remember_rule ?? null,
          updated_input: input.updated_input ?? null,
        },
      };
      server.emit({ delay: 0, event: response });
      server.play(rest);
      return HttpResponse.json<Schemas['SendEventsResponse']>(
        { data: [server.events.at(-1) ?? response] },
        { status: 202 },
      );
    }),
  ];
}
