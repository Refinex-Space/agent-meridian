import { describe, expect, it } from 'vitest';

import { parseSse, type SseFrame } from './parse-sse';

function streamOf(chunks: string[]): ReadableStream<Uint8Array> {
  const encoder = new TextEncoder();
  return new ReadableStream({
    start(controller) {
      for (const chunk of chunks) controller.enqueue(encoder.encode(chunk));
      controller.close();
    },
  });
}

async function collect(chunks: string[]): Promise<SseFrame[]> {
  const frames: SseFrame[] = [];
  for await (const frame of parseSse(streamOf(chunks))) frames.push(frame);
  return frames;
}

describe('parseSse', () => {
  it('解析带 id 的持久化事件与不带 id 的流上事件，跳过注释', async () => {
    const frames = await collect([
      ':open\n\n',
      'event: session.status_running\nid: 41\ndata: {"seq":41}\n\n',
      'event: ping\ndata: {}\n\n',
    ]);
    expect(frames).toEqual([
      { event: 'session.status_running', id: '41', data: '{"seq":41}' },
      { event: 'ping', id: null, data: '{}' },
    ]);
  });

  it('处理跨分块的行、CRLF 与多行 data', async () => {
    const frames = await collect([
      'event: agent.mes',
      'sage\r\nid: 7\r',
      '\ndata: 第一行\r\ndata: 第二行\r\n\r\n',
    ]);
    expect(frames).toEqual([{ event: 'agent.message', id: '7', data: '第一行\n第二行' }]);
  });

  it('流在消息中途结束时仍输出已收到的完整字段', async () => {
    const frames = await collect(['event: ping\ndata: {}']);
    expect(frames).toEqual([{ event: 'ping', id: null, data: '{}' }]);
  });
});
