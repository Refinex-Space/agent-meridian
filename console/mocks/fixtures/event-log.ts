import type { EventOf, SessionEvent, SessionEventType } from '@/lib/api/types';

/** 构造一段会话事件：seq 连续递增，时间按给定的间隔推进，结构完全来自生成的类型。 */
export class EventLog {
  readonly events: SessionEvent[] = [];
  readonly sessionId: string;
  private seq: number;
  private clock: number;

  constructor(sessionId: string, start: Date, firstSeq = 1) {
    this.sessionId = sessionId;
    this.seq = firstSeq;
    this.clock = start.getTime();
  }

  /** 当前时钟（毫秒）。 */
  get now(): number {
    return this.clock;
  }

  /** 推进时钟。 */
  wait(ms: number): this {
    this.clock += ms;
    return this;
  }

  /** 追加一个事件，`after` 毫秒之后发生。 */
  add<T extends SessionEventType>(
    type: T,
    data: EventOf<T>['data'],
    where: { thread: string | null; turn: string | null; after?: number },
  ): EventOf<T> {
    this.clock += where.after ?? 0;
    const seq = this.seq++;
    const event = {
      id: eventId(this.sessionId, seq),
      seq,
      session_id: this.sessionId,
      thread_id: where.thread,
      turn_id: where.turn,
      type,
      created_at: new Date(this.clock).toISOString(),
      data,
    } as EventOf<T>;
    // 上面的断言是安全的：type 与 data 都受 T 约束，信封字段对所有事件相同。
    this.events.push(event);
    return event;
  }
}

/** 确定的事件 ID：会话后缀加 seq，便于测试与故事稳定引用。 */
export function eventId(sessionId: string, seq: number): string {
  return `evt_${sessionId.slice(4, 14)}${String(seq).padStart(6, '0')}`;
}

/** 一次模型调用的 token 用量。 */
export function usage(input: number, output: number, cached = 0, reasoning = 0) {
  return {
    input_tokens: input,
    output_tokens: output,
    cached_tokens: cached,
    cache_creation_tokens: 0,
    reasoning_tokens: reasoning,
  };
}

/** 文本内容块。 */
export function text(value: string) {
  return [{ type: 'text' as const, text: value }];
}
