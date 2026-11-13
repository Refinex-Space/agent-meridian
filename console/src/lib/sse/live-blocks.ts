import type { SessionEvent, StreamOnlyEvent } from '@/lib/api/types';

/** 正在生成的内容块（只在流上的 event_start、event_delta 拼成）。 */
export interface LiveBlock {
  blockId: string;
  kind: 'text' | 'thinking' | 'data' | 'tool_use' | 'tool_result';
  /** 工具调用与工具结果块的工具名。 */
  name: string | null;
  threadId: string | null;
  text: string;
}

/**
 * 按收到的流上事件与持久化事件更新正在生成的块：增量追加到对应的块；同一块的持久化事件
 * （agent.message、agent.thinking、agent.tool_use、agent.tool_result）到达时移除；一轮停下时全部清空。
 * 工具调用块与工具结果块共用工具调用 ID，所以键带上种类。
 */
export function applyToLiveBlocks(
  blocks: readonly LiveBlock[],
  event: SessionEvent | StreamOnlyEvent,
): readonly LiveBlock[] {
  switch (event.type) {
    case 'event_start':
      return [
        ...blocks.filter((b) => !(b.blockId === event.data.block_id && b.kind === event.data.kind)),
        {
          blockId: event.data.block_id,
          kind: event.data.kind,
          name: event.data.name,
          threadId: event.thread_id,
          text: '',
        },
      ];
    case 'event_delta': {
      const found = blocks.some((b) => b.blockId === event.data.block_id && b.kind === event.data.kind);
      if (!found) {
        return [
          ...blocks,
          {
            blockId: event.data.block_id,
            kind: event.data.kind,
            name: null,
            threadId: event.thread_id,
            text: event.data.delta,
          },
        ];
      }
      return blocks.map((b) =>
        b.blockId === event.data.block_id && b.kind === event.data.kind
          ? { ...b, text: b.text + event.data.delta }
          : b,
      );
    }
    case 'ping':
      return blocks;
    case 'agent.message':
      return blocks.filter(
        (b) => !(b.blockId === event.data.block_id && (b.kind === 'text' || b.kind === 'data')),
      );
    case 'agent.thinking':
      return blocks.filter((b) => !(b.blockId === event.data.block_id && b.kind === 'thinking'));
    case 'agent.tool_use':
    case 'agent.mcp_tool_use':
    case 'agent.custom_tool_use':
      return blocks.filter((b) => !(b.blockId === event.data.tool_use_id && b.kind === 'tool_use'));
    case 'agent.tool_result':
    case 'agent.mcp_tool_result':
      return blocks.filter((b) => !(b.blockId === event.data.tool_use_id && b.kind === 'tool_result'));
    case 'session.status_idle':
    case 'session.status_requires_action':
    case 'session.status_rescheduled':
    case 'session.status_terminated':
      return blocks.length === 0 ? blocks : [];
    default:
      return blocks;
  }
}
