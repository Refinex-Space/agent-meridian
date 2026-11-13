import { ApiError } from '@/lib/api/sessions';
import type { Problem } from '@/lib/api/types';

export interface ErrorDescription {
  /** 发生了什么。 */
  title: string;
  /** 服务端给出的原因（problem+json 的 detail）。 */
  detail: string | null;
  /** 用户下一步能做什么。 */
  next: string;
  requestId: string | null;
  status: number | null;
  /** 字段校验失败的明细。 */
  violations: Problem['errors'];
}

function isProblem(value: unknown): value is Problem {
  return (
    typeof value === 'object' &&
    value !== null &&
    'status' in value &&
    'request_id' in value &&
    'code' in value
  );
}

/** 按 HTTP 状态给出"发生了什么"与"下一步"。 */
function byStatus(status: number | null): { title: string; next: string } {
  if (status === null) return { title: '无法连接到服务', next: '检查网络连接后重试。' };
  if (status === 400 || status === 422) return { title: '请求的内容有误', next: '按提示修改后重新提交。' };
  if (status === 401) return { title: '登录已过期', next: '重新登录后再试。' };
  if (status === 403) return { title: '没有权限', next: '请联系项目管理员授予所需的角色。' };
  if (status === 404) return { title: '要找的对象不存在', next: '它可能已被删除，返回列表查看。' };
  if (status === 409) return { title: '与当前状态冲突', next: '刷新后再试。' };
  if (status === 413) return { title: '内容超过大小限制', next: '缩小内容后重新提交。' };
  if (status === 429) return { title: '请求过于频繁', next: '稍等片刻后重试。' };
  if (status >= 500)
    return { title: '服务暂时不可用', next: '稍后重试；问题持续时把请求 ID 提供给平台管理员。' };
  return { title: '操作没有完成', next: '稍后重试。' };
}

/**
 * 把请求错误映射为界面上的说明：problem+json（或带它的 ApiError）给出原因与 request_id；
 * fetch 自身抛出的 TypeError 视为网络不可达。
 */
export function describeError(error: unknown): ErrorDescription {
  const problem = error instanceof ApiError ? error.problem : isProblem(error) ? error : null;
  const status = error instanceof ApiError ? error.status : (problem?.status ?? null);
  const network = error instanceof TypeError;
  const { title, next } = byStatus(network ? null : status);
  return {
    title,
    detail: problem?.detail ?? (network || problem ? null : error instanceof Error ? error.message : null),
    next,
    requestId: problem?.request_id ?? null,
    status: network ? null : status,
    violations: problem?.errors,
  };
}
