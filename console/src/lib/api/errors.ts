import type { AdminSchemas, Problem, ResourceReference } from './types';

/** 请求失败：带 HTTP 状态、服务端返回的 problem+json（如果有）与请求 ID。 */
export class ApiError extends Error {
  readonly status: number;
  readonly problem: Problem | null;
  /** problem+json 的 `request_id`；响应不是 problem+json 时取 `X-Request-Id` 响应头。 */
  readonly requestId: string | null;

  constructor(status: number, problem: Problem | null, requestId: string | null = null) {
    super(problem?.detail ?? problem?.title ?? `请求失败（HTTP ${status}）`);
    this.name = 'ApiError';
    this.status = status;
    this.problem = problem;
    this.requestId = problem?.request_id ?? requestId;
  }

  /** 由失败的响应与已解析的响应体构造。 */
  static from(response: Response, body: unknown): ApiError {
    return new ApiError(response.status, isProblem(body) ? body : null, response.headers.get('X-Request-Id'));
  }
}

/** 形如 RFC 9457 problem+json（带扩展字段 `code` 与 `request_id`）的对象。 */
export function isProblem(value: unknown): value is Problem {
  return (
    typeof value === 'object' &&
    value !== null &&
    'status' in value &&
    'request_id' in value &&
    'code' in value
  );
}

/** fetch 自身抛出的错误（网络不可达、跨域被拒）：与服务端返回的错误区分。 */
export function isNetworkError(error: unknown): boolean {
  return error instanceof TypeError;
}

/**
 * 删除被引用的资源时（409 `*.in_use`）管理 API 返回的引用方。管理 API 的 problem+json 比公开 API 多 `references`，`ApiError`
 * 统一按公开 API 的类型保存，这里按管理 API 的结构读出；不是这类错误时返回 null。
 */
export function inUseReferences(error: unknown): ResourceReference[] | null {
  if (!(error instanceof ApiError) || !error.problem?.code.endsWith('.in_use')) return null;
  const problem = error.problem as AdminSchemas['Problem'];
  return problem.references ?? [];
}
