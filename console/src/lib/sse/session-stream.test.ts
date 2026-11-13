import { describe, expect, it, vi } from 'vitest';

import type { SessionEvent } from '@/lib/api/types';

import { runSessionStream, type StreamState } from './session-stream';

function frame(seq: number, type = 'session.status_running'): string {
  const event = {
    id: `evt_${seq}`,
    seq,
    session_id: 'ses_1',
    thread_id: null,
    turn_id: 'trn_1',
    type,
    created_at: '2026-11-13T02:00:00.000Z',
    data: type === 'session.status_terminated' ? { reason: 'archived' } : {},
  };
  return `event: ${type}\nid: ${seq}\ndata: ${JSON.stringify(event)}\n\n`;
}

function response(body: string, status = 200): Response {
  return new Response(body, { status, headers: { 'Content-Type': 'text/event-stream' } });
}

describe('runSessionStream', () => {
  it('按 seq 去重，断线后带 Last-Event-ID 重连，收到终止事件后结束', async () => {
    const requests: (string | null)[] = [];
    const bodies = [
      `:open\n\n${frame(1)}${frame(2)}`,
      `${frame(2)}${frame(3)}${frame(4, 'session.status_terminated')}`,
    ];
    const fetchImpl = vi.fn((_url: RequestInfo | URL, init?: RequestInit) => {
      const headers = (init?.headers ?? {}) as Record<string, string>;
      requests.push(headers['Last-Event-ID'] ?? null);
      return Promise.resolve(response(bodies.shift() ?? ''));
    });
    const events: SessionEvent[] = [];
    const states: StreamState[] = [];
    await runSessionStream({
      url: '/v1/sessions/ses_1/stream',
      lastSeq: 0,
      onEvent: (e) => events.push(e),
      onState: (s) => states.push(s),
      signal: new AbortController().signal,
      retry: { initialMs: 1, maxMs: 1 },
      fetchImpl,
    });
    expect(events.map((e) => e.seq)).toEqual([1, 2, 3, 4]);
    expect(requests).toEqual([null, '2']);
    expect(states).toEqual(['connecting', 'open', 'reconnecting', 'open', 'closed']);
  });

  it('请求被拒绝（401、403、404）时不重连', async () => {
    const fetchImpl = vi.fn(() => Promise.resolve(response('', 404)));
    const states: StreamState[] = [];
    await runSessionStream({
      url: '/v1/sessions/ses_x/stream',
      lastSeq: 5,
      onEvent: () => undefined,
      onState: (s) => states.push(s),
      signal: new AbortController().signal,
      fetchImpl,
    });
    expect(fetchImpl).toHaveBeenCalledTimes(1);
    expect(states).toEqual(['connecting', 'closed']);
  });

  it('网络错误按退避重连，中止后退出', async () => {
    const controller = new AbortController();
    let calls = 0;
    const fetchImpl = vi.fn(() => {
      calls += 1;
      if (calls === 3) controller.abort();
      return Promise.reject(new TypeError('network'));
    });
    await runSessionStream({
      url: '/v1/sessions/ses_1/stream',
      lastSeq: 0,
      onEvent: () => undefined,
      signal: controller.signal,
      retry: { initialMs: 1, maxMs: 2 },
      fetchImpl,
    });
    expect(calls).toBe(3);
  });
});
