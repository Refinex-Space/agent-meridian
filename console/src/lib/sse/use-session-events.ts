import { useCallback, useEffect, useState } from 'react';

import { authorizedFetch, v1Api } from '@/lib/api/client';
import { getSession, listAllEvents } from '@/lib/api/sessions';
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

export interface SessionEvents extends SessionEventsState {
  /** 已断开（被拒绝或会话终止之外的原因）后手动重连，给 StreamIndicator 的"重新连接"。 */
  reconnect: () => void;
}

export interface SessionEventsOptions {
  /** 当前项目：请求带 `Meridian-Project`。不给时不带项目头（MSW 应答的原型）。 */
  projectId?: string;
}

const STATUS_BY_EVENT: Partial<Record<SessionEvent['type'], SessionStatus>> = {
  'session.status_running': 'running',
  'session.status_idle': 'idle',
  'session.status_requires_action': 'requires_action',
  'session.status_rescheduled': 'rescheduled',
  'session.status_terminated': 'terminated',
};

const INITIAL: SessionEventsState = {
  session: null,
  events: [],
  live: [],
  status: null,
  stream: 'connecting',
  error: null,
};

/**
 * 会话详情的数据：读会话与全部历史事件，再从最后一个 seq 订阅会话流（带增量），断线自动续传。事件由 `runSessionStream`
 * 去重并按序交付；正在生成的内容块单独维护，持久化事件到达后移除。连接状态交给 `StreamIndicator` 显示。
 */
export function useSessionEvents(sessionId: string, { projectId }: SessionEventsOptions = {}): SessionEvents {
  const [state, setState] = useState<SessionEventsState>(INITIAL);
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    const request = { client: v1Api(projectId), signal: controller.signal };
    void (async () => {
      try {
        const [session, history] = await Promise.all([
          getSession(sessionId, request),
          listAllEvents(sessionId, request),
        ]);
        if (controller.signal.aborted) return;
        setState((s) => ({ ...s, session, events: history, status: session.status, error: null }));
        if (session.status === 'terminated') {
          setState((s) => ({ ...s, stream: 'closed' }));
          return;
        }
        await runSessionStream({
          url: `/v1/sessions/${encodeURIComponent(sessionId)}/stream?event_deltas=true`,
          lastSeq: history.at(-1)?.seq ?? 0,
          fetchImpl: authorizedFetch(projectId),
          signal: controller.signal,
          onState: (stream) => setState((s) => ({ ...s, stream })),
          onEvent: (event) =>
            setState((s) => ({
              ...s,
              events: [...s.events, event],
              live: applyToLiveBlocks(s.live, event),
              status: STATUS_BY_EVENT[event.type] ?? s.status,
            })),
          onStreamOnly: (event) => setState((s) => ({ ...s, live: applyToLiveBlocks(s.live, event) })),
        });
      } catch (error) {
        if (controller.signal.aborted) return;
        const failure = error instanceof Error ? error : new Error(String(error));
        setState((s) => ({ ...s, error: failure, stream: 'closed' }));
      }
    })();
    return () => controller.abort();
  }, [sessionId, projectId, attempt]);

  const reconnect = useCallback(() => {
    setState(INITIAL);
    setAttempt((n) => n + 1);
  }, []);

  return { ...state, reconnect };
}
