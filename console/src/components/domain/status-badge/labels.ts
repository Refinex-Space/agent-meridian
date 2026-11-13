/**
 * 状态的文字与语义（frontend-design.md「语义色与状态映射」）：全控制台的会话、轮次、工具调用、待处理动作都从这里取，
 * 任何页面不另起一套。语义决定状态符号的形状与颜色（StatusGlyph），文字按用户理解的概念写。
 */
import type {
  ConnectivityCheck,
  PendingActionStatus,
  Schemas,
  SessionStatus,
  StopReason,
  ToolResultState,
} from '@/lib/api/types';

import type { StatusKind } from './status-glyph';

export interface StatusMeaning {
  kind: StatusKind;
  label: string;
}

export const SESSION_STATUS_LABEL: Record<SessionStatus, string> = {
  idle: '空闲',
  running: '运行中',
  requires_action: '等待处理',
  rescheduled: '重新调度',
  terminated: '已终止',
};

/** 一轮的停止原因（`session.status_idle` 的 `stop_reason`）。 */
export const STOP_REASON_LABEL: Record<StopReason, string> = {
  end_turn: '正常结束',
  interrupted: '被中断',
  max_turns: '达到推理次数上限',
  budget_exceeded: '预算耗尽',
  tools_denied: '工具调用全部被拒绝',
  error: '出错',
};

export function sessionStatus(status: SessionStatus): StatusMeaning {
  return { kind: status, label: SESSION_STATUS_LABEL[status] };
}

/** 轮次：运行中，或按停止原因结束（正常结束为空闲，被中断为已终止，其余为失败）。 */
export function turnStatus(status: 'running' | StopReason): StatusMeaning {
  if (status === 'running') return { kind: 'running', label: '运行中' };
  const kind: StatusKind =
    status === 'end_turn' ? 'idle' : status === 'interrupted' ? 'terminated' : 'failure';
  return { kind, label: STOP_REASON_LABEL[status] };
}

/** 工具调用：结果状态，加上还没有结果、正在等人批准的 `awaiting`。 */
export function toolStatus(status: ToolResultState | 'awaiting'): StatusMeaning {
  switch (status) {
    case 'awaiting':
      return { kind: 'requires_action', label: '等待批准' };
    case 'running':
      return { kind: 'running', label: '执行中' };
    case 'success':
      return { kind: 'success', label: '成功' };
    case 'error':
      return { kind: 'failure', label: '出错' };
    case 'denied':
      return { kind: 'failure', label: '被拒绝' };
    case 'interrupted':
      return { kind: 'terminated', label: '被中断' };
  }
}

/**
 * 待处理动作：等待回应、已失效（随会话终止作废，没有结论），或按结论写明结果。超时的动作状态是 `expired` 但带有
 * `origin=timeout` 的结论（runtime 的 PendingActionRepository.conclude），按结论显示"超时视为拒绝"等。
 */
export function actionStatus(
  status: PendingActionStatus,
  conclusion: Schemas['PendingActionConclusion'] | null = null,
): StatusMeaning {
  if (status === 'pending') return { kind: 'requires_action', label: '等待回应' };
  if (conclusion === null) return { kind: 'terminated', label: '已失效' };
  if (conclusion.approved === true) return { kind: 'success', label: '已批准' };
  if (conclusion.approved === false) {
    const label =
      conclusion.origin === 'timeout'
        ? '超时视为拒绝'
        : conclusion.origin === 'unattended'
          ? '无人值守自动拒绝'
          : conclusion.origin === 'interrupt'
            ? '中断时拒绝'
            : '已拒绝';
    return { kind: 'failure', label };
  }
  if (conclusion.decision === 'retry') return { kind: 'success', label: '重试' };
  if (conclusion.decision === 'skip') return { kind: 'idle', label: '跳过' };
  if (conclusion.error === true) return { kind: 'failure', label: '回传了错误' };
  return { kind: 'success', label: '已回传结果' };
}

/**
 * 连通性检查（供应商账号的连通性测试、MCP 服务的探测，`ConnectivityCheck`）的结果；从未检查过为空闲。
 */
export function connectivityStatus(check: ConnectivityCheck | null): StatusMeaning {
  if (check === null) return { kind: 'idle', label: '未测试' };
  switch (check.status) {
    case 'ok':
      return { kind: 'success', label: '连通' };
    case 'auth_failed':
      return { kind: 'failure', label: '凭据无效' };
    case 'unreachable':
      return { kind: 'failure', label: '连不上' };
    case 'error':
      return { kind: 'failure', label: '出错' };
  }
}
