import { useEffect, useState } from 'react';

import { getSession, listAllEvents, type RequestOptions } from '@/lib/api/sessions';
import type { Session, SessionEvent, SessionStatus } from '@/lib/api/types';

import { applyToLiveBlocks, type LiveBlock } from './live-blocks';
import { runSessionStream, type StreamState } from './session-stream';

export interface SessionEventsState {
  session: Session | null;
  events: readonly SessionEvent[];
  live: readonly LiveBlock[];
  /** 会话状态：先取会话资源中的值，之后随 `session.status_*` 事件推进。 */
  status: SessionStatus | null;
  stream: StreamState;
  error: Error | null;
}

const STATUS_BY_EVENT: Partial<Record<SessionEvent['type'], SessionStatus>> = {
  'session.status_running': 'running',
  'session.status_idle': 'idle',
  'session.status_requires_action': 'requires_action',
  'session.status_rescheduled': 'rescheduled',
  'session.status_terminated': 'terminated',
};

/**
 * 会话详情的数据：读会话与全部历史事件，再从最后一个 seq 订阅事件流（带增量），断线自动续传。
 * 事件按 seq 去重追加；正在生成的内容块单独维护，持久化事件到达后移除。
 */
export function useSessionEvents(
  sessionId: string,
  options: Omit<RequestOptions, 'signal'> = {},
): SessionEventsState {
  const [state, setState] = useState<SessionEventsState>({
    session: null,
    events: [],
    live: [],
    status: null,
    stream: 'connecting',
    error: null,
  });
  const { baseUrl = '/v1', headers } = options;

  useEffect(() => {
    const controller = new AbortController();
    const request = { baseUrl, ...(headers ? { headers } : {}), signal: controller.signal };
    void (async () => {
      try {
        const [session, history] = await Promise.all([
          getSession(sessionId, request),
          listAllEvents(sessionId, request),
        ]);
        if (controller.signal.aborted) return;
        setState((s) => ({ ...s, session, events: history, status: session.status }));
        if (session.status === 'terminated') {
          setState((s) => ({ ...s, stream: 'closed' }));
          return;
        }
        await runSessionStream({
          url: `${baseUrl}/sessions/${encodeURIComponent(sessionId)}/stream?event_deltas=true`,
          lastSeq: history.at(-1)?.seq ?? 0,
          ...(headers ? { headers } : {}),
          signal: controller.signal,
          onState: (stream) => setState((s) => ({ ...s, stream })),
          onEvent: (event) =>
            setState((s) => {
              if (s.events.length > 0 && event.seq <= (s.events.at(-1)?.seq ?? 0)) return s;
              return {
                ...s,
                events: [...s.events, event],
                live: applyToLiveBlocks(s.live, event),
                status: STATUS_BY_EVENT[event.type] ?? s.status,
              };
            }),
          onStreamOnly: (event) => setState((s) => ({ ...s, live: applyToLiveBlocks(s.live, event) })),
        });
      } catch (error) {
        if (controller.signal.aborted) return;
        const failure = error instanceof Error ? error : new Error(String(error));
        setState((s) => ({ ...s, error: failure, stream: 'closed' }));
      }
    })();
    return () => controller.abort();
  }, [sessionId, baseUrl, headers]);

  return state;
}
