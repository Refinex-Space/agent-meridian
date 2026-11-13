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

/** 一个连接的响应体：依次推送 `frames`，然后按 `end` 结束——正常关闭、出错断开，或保持打开直到请求被中止。 */
function streamResponse(
  frames: string[],
  end: 'close' | 'error' | 'hold',
  signal?: AbortSignal | null,
): Response {
  const encoder = new TextEncoder();
  const chunks = [':open\n\n', ...frames].map((f) => encoder.encode(f));
  // 按读取逐块推送：出错发生在前面的块都被读走之后（start 中直接 error 会丢弃已排队的块）。
  const body = new ReadableStream<Uint8Array>({
    pull(controller) {
      const chunk = chunks.shift();
      if (chunk) {
        controller.enqueue(chunk);
        return undefined;
      }
      if (end === 'close') controller.close();
      else if (end === 'error') controller.error(new TypeError('network'));
      else
        return new Promise<void>((resolve) => {
          signal?.addEventListener('abort', () => {
            controller.error(new DOMException('aborted', 'AbortError'));
            resolve();
          });
        });
      return undefined;
    },
  });
  return new Response(body, { status: 200, headers: { 'Content-Type': 'text/event-stream' } });
}

interface Connection {
  lastEventId: string | null;
}

/** 按连接顺序给出响应的假服务端，记录每次连接带的 Last-Event-ID。 */
function fakeServer(responses: ((signal: AbortSignal | null | undefined) => Response)[]) {
  const connections: Connection[] = [];
  const fetchImpl = vi.fn((_url: RequestInfo | URL, init?: RequestInit) => {
    const headers = (init?.headers ?? {}) as Record<string, string>;
    connections.push({ lastEventId: headers['Last-Event-ID'] ?? null });
    const next = responses.shift();
    return Promise.resolve(next ? next(init?.signal) : streamResponse([], 'hold', init?.signal));
  });
  return { fetchImpl, connections };
}

async function collect(
  fetchImpl: typeof fetch,
  options: { lastSeq?: number; contiguous?: boolean; signal?: AbortSignal } = {},
): Promise<number[]> {
  const seqs: number[] = [];
  await runSessionStream({
    url: '/v1/sessions/ses_1/stream',
    lastSeq: options.lastSeq ?? 0,
    contiguous: options.contiguous ?? true,
    gapTimeoutMs: 20,
    onEvent: (e) => seqs.push(e.seq),
    signal: options.signal ?? new AbortController().signal,
    retry: { initialMs: 1, maxMs: 4 },
    fetchImpl,
  });
  return seqs;
}

const range = (from: number, to: number) => Array.from({ length: to - from + 1 }, (_, i) => from + i);
const END = 'session.status_terminated';

describe('runSessionStream：断线、乱序、重复、跳号（T21-A05）', () => {
  it('断线：流在中途出错后按退避重连，带最后交付的 seq，序列连续不重复', async () => {
    const { fetchImpl, connections } = fakeServer([
      () => streamResponse([frame(1), frame(2)], 'error'),
      () => streamResponse([frame(3), frame(4, END)], 'close'),
    ]);
    expect(await collect(fetchImpl)).toEqual([1, 2, 3, 4]);
    expect(connections.map((c) => c.lastEventId)).toEqual([null, '2']);
  });

  it('乱序：先到的较大 seq 暂存，缺的事件到达后按序交付', async () => {
    const { fetchImpl, connections } = fakeServer([
      () => streamResponse([frame(1), frame(3), frame(4), frame(2), frame(6, END), frame(5)], 'close'),
    ]);
    expect(await collect(fetchImpl)).toEqual([1, 2, 3, 4, 5, 6]);
    expect(connections).toHaveLength(1);
  });

  it('重复：补发与实时推送交界处的重复事件、暂存区里已有的事件都只交付一次', async () => {
    const { fetchImpl } = fakeServer([
      () => streamResponse([frame(1), frame(2), frame(2), frame(1), frame(4), frame(4), frame(3)], 'close'),
      () => streamResponse([frame(2), frame(3), frame(4), frame(5, END)], 'close'),
    ]);
    expect(await collect(fetchImpl, { lastSeq: 0 })).toEqual([1, 2, 3, 4, 5]);
  });

  it('跳号：缺失的事件等不到时断开重连，由服务端从 Last-Event-ID 之后补发', async () => {
    const { fetchImpl, connections } = fakeServer([
      // 3 丢了；连接保持打开，直到客户端因跳号中止它。
      (signal) => streamResponse([frame(1), frame(2), frame(4), frame(5)], 'hold', signal),
      () => streamResponse([frame(3), frame(4), frame(5), frame(6, END)], 'close'),
    ]);
    expect(await collect(fetchImpl)).toEqual([1, 2, 3, 4, 5, 6]);
    expect(connections.map((c) => c.lastEventId)).toEqual([null, '2']);
  });

  it('从中途开始订阅：只交付 lastSeq 之后的事件', async () => {
    const { fetchImpl, connections } = fakeServer([
      () => streamResponse([frame(40), frame(41), frame(42, END)], 'close'),
    ]);
    expect(await collect(fetchImpl, { lastSeq: 41 })).toEqual([42]);
    expect(connections[0]?.lastEventId).toBe('41');
  });

  it('不连续的流（线程流、threads=main）只去重，不等待也不判断跳号', async () => {
    const { fetchImpl, connections } = fakeServer([
      () => streamResponse([frame(3), frame(7), frame(7), frame(5), frame(9, END)], 'close'),
    ]);
    expect(await collect(fetchImpl, { contiguous: false })).toEqual([3, 7, 9]);
    expect(connections).toHaveLength(1);
  });

  it('四种情况随机混合：最终事件序列连续且不重复', async () => {
    // 固定种子的伪随机数，结果可复现。
    let seed = 20261113;
    const random = () => {
      seed = (seed * 1103515245 + 12345) % 2147483648;
      return seed / 2147483648;
    };
    const total = 300;
    const { fetchImpl, connections } = fakeServer([]);
    // 每次连接：从 Last-Event-ID 之后补发，混入重复、局部乱序与丢失，随机地断开或保持打开。
    fetchImpl.mockImplementation((_url: RequestInfo | URL, init?: RequestInit) => {
      const headers = (init?.headers ?? {}) as Record<string, string>;
      const from = Number(headers['Last-Event-ID'] ?? 0) + 1;
      connections.push({ lastEventId: headers['Last-Event-ID'] ?? null });
      const count = 1 + Math.floor(random() * 40);
      const seqs: number[] = [];
      for (let seq = from; seq < Math.min(total + 1, from + count); seq += 1) {
        const roll = random();
        if (roll < 0.08) continue; // 跳号
        seqs.push(seq);
        if (roll > 0.9) seqs.push(seq - Math.floor(random() * 3)); // 重复（含更早的事件）
      }
      for (let i = 0; i + 1 < seqs.length; i += 1) {
        if (random() < 0.15) [seqs[i], seqs[i + 1]] = [seqs[i + 1]!, seqs[i]!]; // 乱序
      }
      let end: 'close' | 'error' | 'hold' = random() < 0.5 ? 'error' : random() < 0.5 ? 'close' : 'hold';
      // 保持打开的连接只在客户端发现跳号后才会被中止：去掉第一个事件，确保有缺口；没有可发的事件时正常关闭。
      const sent = end === 'hold' ? seqs.filter((s) => s !== from) : seqs;
      if (end === 'hold' && !sent.some((s) => s > from)) end = 'close';
      const frames = sent.filter((s) => s >= 1).map((s) => frame(s, s === total ? END : undefined));
      return Promise.resolve(streamResponse(frames, end, init?.signal));
    });
    const seqs = await collect(fetchImpl);
    expect(seqs).toEqual(range(1, total));
    expect(connections.length).toBeGreaterThan(5);
  });
});
