import { describe, expect, it } from 'vitest';

import type { SessionEvent, StreamOnlyEvent } from '@/lib/api/types';

import { applyToLiveBlocks, type LiveBlock } from './live-blocks';

const envelope = {
  session_id: 'ses_1',
  thread_id: 'thr_1',
  turn_id: 'trn_1',
  created_at: '2026-11-13T02:00:00.000Z',
};

function start(blockId: string, kind: 'text' | 'tool_use', name: string | null = null): StreamOnlyEvent {
  return { ...envelope, id: null, seq: null, type: 'event_start', data: { block_id: blockId, kind, name } };
}

function delta(blockId: string, kind: 'text' | 'tool_use', text: string): StreamOnlyEvent {
  return {
    ...envelope,
    id: null,
    seq: null,
    type: 'event_delta',
    data: { block_id: blockId, kind, delta: text },
  };
}

function apply(events: (SessionEvent | StreamOnlyEvent)[]): readonly LiveBlock[] {
  return events.reduce<readonly LiveBlock[]>((blocks, e) => applyToLiveBlocks(blocks, e), []);
}

describe('applyToLiveBlocks', () => {
  it('增量拼到对应的块上，持久化的 agent.message 到达后移除该块', () => {
    const blocks = apply([
      start('blk_1', 'text'),
      delta('blk_1', 'text', '已导出'),
      delta('blk_1', 'text', '到工作区'),
    ]);
    expect(blocks).toEqual([
      { blockId: 'blk_1', kind: 'text', name: null, threadId: 'thr_1', text: '已导出到工作区' },
    ]);
    const message: SessionEvent = {
      ...envelope,
      id: 'evt_9',
      seq: 9,
      type: 'agent.message',
      data: { block_id: 'blk_1', content: [{ type: 'text', text: '已导出到工作区' }] },
    };
    expect(applyToLiveBlocks(blocks, message)).toEqual([]);
  });

  it('工具调用块按种类区分，一轮停下时清空', () => {
    const blocks = apply([
      start('toolu_1', 'tool_use', 'bash'),
      delta('toolu_1', 'tool_use', '{"command":"ls"'),
    ]);
    expect(blocks[0]).toMatchObject({ kind: 'tool_use', name: 'bash', text: '{"command":"ls"' });
    const idle: SessionEvent = {
      ...envelope,
      id: 'evt_10',
      seq: 10,
      type: 'session.status_idle',
      data: { stop_reason: 'end_turn' },
    };
    expect(applyToLiveBlocks(blocks, idle)).toEqual([]);
  });
});
