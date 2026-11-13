/** SSE 的一条消息（架构设计「SSE 协议」：`event:` 为类型，`id:` 为 seq，`data:` 为事件信封 JSON）。 */
export interface SseFrame {
  event: string;
  /** 持久化事件的 seq；只在流上的事件（event_start、event_delta、ping）没有。 */
  id: string | null;
  data: string;
}

/**
 * 按 WHATWG 的事件流格式逐条解析：空行结束一条消息，`:` 开头的行是注释（服务端连接建立时的 `:open`），
 * 多行 `data:` 以换行拼接；兼容 CRLF、CR、LF 三种换行与跨分块的行。
 */
export async function* parseSse(stream: ReadableStream<Uint8Array>): AsyncGenerator<SseFrame> {
  const reader = stream.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  let event = '';
  let id: string | null = null;
  let data: string[] = [];

  function* flushLine(line: string): Generator<SseFrame> {
    if (line === '') {
      if (data.length > 0) yield { event: event || 'message', id, data: data.join('\n') };
      event = '';
      id = null;
      data = [];
      return;
    }
    if (line.startsWith(':')) return;
    const colon = line.indexOf(':');
    const field = colon === -1 ? line : line.slice(0, colon);
    let value = colon === -1 ? '' : line.slice(colon + 1);
    if (value.startsWith(' ')) value = value.slice(1);
    if (field === 'event') event = value;
    else if (field === 'data') data.push(value);
    else if (field === 'id') id = value;
  }

  try {
    for (;;) {
      const { value, done } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      let match: RegExpExecArray | null;
      const lineBreak = /\r\n|\r|\n/g;
      let consumed = 0;
      while ((match = lineBreak.exec(buffer)) !== null) {
        // 分块恰好落在 CRLF 中间时，等下一块再判断。
        if (match[0] === '\r' && match.index === buffer.length - 1) break;
        yield* flushLine(buffer.slice(consumed, match.index));
        consumed = match.index + match[0].length;
      }
      buffer = buffer.slice(consumed);
    }
    buffer += decoder.decode();
    if (buffer !== '') yield* flushLine(buffer);
    yield* flushLine('');
  } finally {
    reader.releaseLock();
  }
}
