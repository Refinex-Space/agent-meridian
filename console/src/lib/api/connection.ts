/**
 * 与服务端的连接状态：任何一次请求因网络不可达或网关不可用（502、503、504）失败时记为断开，之后任意一次请求成功时恢复。
 * 应用外壳据此显示"与服务端断开"的横幅（frontend-design.md「通知策略」：影响整个应用的状态用页面顶部横幅）。
 */

export type ConnectionState = 'online' | 'offline';

const GATEWAY_FAILURES = new Set([502, 503, 504]);

let state: ConnectionState = 'online';
const listeners = new Set<() => void>();

function set(next: ConnectionState): void {
  if (next === state) return;
  state = next;
  for (const listener of listeners) listener();
}

/** 一次请求得到了响应：网关不可用记为断开，其余（包括 4xx、500）说明服务端可达。 */
export function reportResponse(status: number): void {
  set(GATEWAY_FAILURES.has(status) ? 'offline' : 'online');
}

/** 一次请求没有得到响应（fetch 抛出 TypeError）。 */
export function reportNetworkFailure(): void {
  set('offline');
}

export function getConnectionState(): ConnectionState {
  return state;
}

/** 供 `useSyncExternalStore` 订阅。 */
export function subscribeConnection(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}
