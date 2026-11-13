import type { SessionEvent, StreamOnlyEvent } from '@/lib/api/types';

import { parseSse } from './parse-sse';

/** 连接状态，供 StreamIndicator 显示：连接中、已连接、重连中、已结束（会话终止或请求被拒绝）。 */
export type StreamState = 'connecting' | 'open' | 'reconnecting' | 'closed';

export interface SessionStreamOptions {
  /** 流地址，例如 `/v1/sessions/ses_…/stream?event_deltas=true`。 */
  url: string;
  /** 已处理的最后一个 seq；连接与重连都以它作为 `Last-Event-ID`，0 表示从头开始。 */
  lastSeq: number;
  /**
   * seq 是否连续（架构设计「SSE 协议」）：会话流（不带 `threads` 过滤）连续，客户端据此发现乱序与跳号；线程流与
   * `?threads=main` 不连续，只按 seq 去重。默认 true。
   */
  contiguous?: boolean;
  /** 等待缺失事件的时长：超过后断开并带 `Last-Event-ID` 重连，由服务端补发缺失的事件。默认 2 秒。 */
  gapTimeoutMs?: number;
  /** 额外的请求头。 */
  headers?: Record<string, string>;
  /** 按 seq 严格递增、不重复地交付（连续的流还保证不跳号）。 */
  onEvent: (event: SessionEvent) => void;
  onStreamOnly?: (event: StreamOnlyEvent) => void;
  onState?: (state: StreamState) => void;
  signal: AbortSignal;
  /** 重连退避：初始与上限，默认 1 秒与 30 秒。 */
  retry?: { initialMs: number; maxMs: number };
  /** 发请求的 fetch；控制台传 `authorizedFetch(projectId)`（带认证与项目头）。 */
  fetchImpl?: typeof fetch;
}

const FATAL_STATUS = new Set([400, 401, 403, 404]);

/** 一段时间内没等到缺失的事件：中止本次连接、立即重连。 */
class GapTimeout extends Error {}

function sleep(ms: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve) => {
    const timer = setTimeout(resolve, ms);
    signal.addEventListener(
      'abort',
      () => {
        clearTimeout(timer);
        resolve();
      },
      { once: true },
    );
  });
}

/**
 * 订阅会话事件流，直到会话终止、请求被拒绝或 `signal` 中止。
 *
 * 架构设计「控制台·技术约定」与「SSE 协议」的客户端一侧：用 `fetch` 流式读取（EventSource 不能带 Authorization）；
 * - 去重：seq 不超过已交付位置的事件丢弃（服务端补发与实时推送的交界处会重复）；
 * - 乱序：连续的流中先到的较大 seq 暂存，前面的事件到齐后按序交付；
 * - 跳号：缺失的事件在 `gapTimeoutMs` 内没有到达时断开重连，`Last-Event-ID` 取已交付的位置，服务端补发其后的全部事件；
 * - 断线：网络错误与 5xx 按指数退避重连；服务端到期关闭（单连接最长 60 分钟）立即重连；
 * - 结束：交付 `session.status_terminated` 或得到 400、401、403、404 后不再重连。
 */
export async function runSessionStream(options: SessionStreamOptions): Promise<void> {
  const { url, signal, onEvent, onStreamOnly, onState } = options;
  const fetchImpl = options.fetchImpl ?? fetch;
  const contiguous = options.contiguous ?? true;
  const gapTimeoutMs = options.gapTimeoutMs ?? 2000;
  const { initialMs, maxMs } = options.retry ?? { initialMs: 1000, maxMs: 30_000 };
  let lastSeq = options.lastSeq;
  let backoff = initialMs;
  let first = true;
  let terminated = false;
  /** 连续的流中先到的事件，按 seq 暂存，前面的到齐后交付。 */
  const pending = new Map<number, SessionEvent>();

  const deliver = (event: SessionEvent) => {
    lastSeq = event.seq;
    onEvent(event);
    if (event.type === 'session.status_terminated') terminated = true;
  };
  const flush = () => {
    let next = pending.get(lastSeq + 1);
    while (next && !terminated) {
      pending.delete(next.seq);
      deliver(next);
      next = pending.get(lastSeq + 1);
    }
  };
  /** 收到一个持久化事件；返回是否出现了等待补齐的缺口。 */
  const accept = (event: SessionEvent): boolean => {
    if (event.seq <= lastSeq || pending.has(event.seq)) return pending.size > 0;
    if (!contiguous || event.seq === lastSeq + 1) {
      deliver(event);
      flush();
    } else {
      pending.set(event.seq, event);
    }
    return pending.size > 0;
  };

  while (!signal.aborted && !terminated) {
    onState?.(first ? 'connecting' : 'reconnecting');
    first = false;
    const connection = new AbortController();
    const abortConnection = () => connection.abort();
    signal.addEventListener('abort', abortConnection, { once: true });
    let gapTimer: ReturnType<typeof setTimeout> | undefined;
    const armGap = (gap: boolean) => {
      if (!gap) {
        clearTimeout(gapTimer);
        gapTimer = undefined;
      } else if (gapTimer === undefined) {
        gapTimer = setTimeout(() => connection.abort(new GapTimeout()), gapTimeoutMs);
      }
    };
    try {
      const headers: Record<string, string> = { Accept: 'text/event-stream', ...options.headers };
      if (lastSeq > 0) headers['Last-Event-ID'] = String(lastSeq);
      const response = await fetchImpl(url, { headers, signal: connection.signal, cache: 'no-store' });
      if (FATAL_STATUS.has(response.status)) {
        onState?.('closed');
        return;
      }
      if (!response.ok || !response.body) throw new Error(`stream responded ${response.status}`);
      onState?.('open');
      backoff = initialMs;
      for await (const frame of parseSse(response.body)) {
        if (frame.id !== null) {
          armGap(accept(JSON.parse(frame.data) as SessionEvent));
          if (terminated) {
            onState?.('closed');
            return;
          }
        } else if (onStreamOnly && frame.event !== 'ping') {
          onStreamOnly(JSON.parse(frame.data) as StreamOnlyEvent);
        }
      }
      // 服务端正常关闭（连接到期）：立即重连，缺口由补发填上。
      continue;
    } catch {
      if (signal.aborted) break;
      // 跳号：立即重连，由服务端补发缺失的事件；其他失败在下面按退避等待。
      if (connection.signal.reason instanceof GapTimeout) continue;
    } finally {
      clearTimeout(gapTimer);
      signal.removeEventListener('abort', abortConnection);
    }
    await sleep(backoff, signal);
    backoff = Math.min(maxMs, backoff * 2);
  }
  onState?.('closed');
}
