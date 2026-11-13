import type { SessionEvent, StreamOnlyEvent } from '@/lib/api/types';

import { parseSse } from './parse-sse';

/** 连接状态，供 StreamIndicator 显示：连接中、已连接、重连中、已结束（会话终止或请求被拒绝）。 */
export type StreamState = 'connecting' | 'open' | 'reconnecting' | 'closed';

export interface SessionStreamOptions {
  /** 会话流地址，例如 `/v1/sessions/ses_…/stream?event_deltas=true`。 */
  url: string;
  /** 已处理的最后一个 seq；连接与重连都以它作为 `Last-Event-ID`，0 表示从头开始。 */
  lastSeq: number;
  /** 额外的请求头（认证由 T21 的 API 客户端提供）。 */
  headers?: Record<string, string>;
  onEvent: (event: SessionEvent) => void;
  onStreamOnly?: (event: StreamOnlyEvent) => void;
  onState?: (state: StreamState) => void;
  signal: AbortSignal;
  /** 重连退避：初始与上限，默认 1 秒与 30 秒。 */
  retry?: { initialMs: number; maxMs: number };
  fetchImpl?: typeof fetch;
}

const FATAL_STATUS = new Set([400, 401, 403, 404]);

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
 * 架构设计「控制台·技术约定」：用 `fetch` 流式读取（EventSource 不能带 Authorization），断线后带 `Last-Event-ID`
 * 重连补齐；按 seq 去重（服务端补发与实时推送交界处可能重复，`?threads=main` 时 seq 不连续，所以只去重不判断跳号）。
 * 服务端在推送 `session.status_terminated` 后关闭连接，此时不再重连。
 */
export async function runSessionStream(options: SessionStreamOptions): Promise<void> {
  const { url, signal, onEvent, onStreamOnly, onState } = options;
  const fetchImpl = options.fetchImpl ?? fetch;
  const { initialMs, maxMs } = options.retry ?? { initialMs: 1000, maxMs: 30_000 };
  let lastSeq = options.lastSeq;
  let backoff = initialMs;
  let first = true;

  while (!signal.aborted) {
    onState?.(first ? 'connecting' : 'reconnecting');
    first = false;
    try {
      const headers: Record<string, string> = { Accept: 'text/event-stream', ...options.headers };
      if (lastSeq > 0) headers['Last-Event-ID'] = String(lastSeq);
      const response = await fetchImpl(url, { headers, signal, cache: 'no-store' });
      if (FATAL_STATUS.has(response.status)) {
        onState?.('closed');
        return;
      }
      if (!response.ok || !response.body) throw new Error(`stream responded ${response.status}`);
      onState?.('open');
      backoff = initialMs;
      for await (const frame of parseSse(response.body)) {
        if (frame.id !== null) {
          const event = JSON.parse(frame.data) as SessionEvent;
          if (event.seq <= lastSeq) continue;
          lastSeq = event.seq;
          onEvent(event);
          if (event.type === 'session.status_terminated') {
            onState?.('closed');
            return;
          }
        } else if (onStreamOnly && frame.event !== 'ping') {
          onStreamOnly(JSON.parse(frame.data) as StreamOnlyEvent);
        }
      }
      // 连接到期（单连接最长 60 分钟）被服务端正常关闭：立即重连。
      continue;
    } catch (error) {
      if (signal.aborted) break;
      void error;
    }
    await sleep(backoff, signal);
    backoff = Math.min(maxMs, backoff * 2);
  }
  onState?.('closed');
}
