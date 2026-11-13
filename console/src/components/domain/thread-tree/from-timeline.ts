import type { SessionStatus } from '@/lib/api/types';

import type { TimelineModel } from '../session-timeline';
import { STOP_REASON_LABEL } from '../status-badge';

import type { ThreadTreeItem } from './thread-tree';

/** 主线程在时间轴模型里没有线程 ID，用这个键代表它。 */
export const MAIN_THREAD_KEY = 'main';

/**
 * 由时间轴模型得到线程树的节点：主线程的状态取会话状态，子线程运行中或已结束；异常结束写出停止原因。
 */
export function threadTreeItems(model: TimelineModel, sessionStatus: SessionStatus): ThreadTreeItem[] {
  // 子线程的父线程是主线程时，父 ID 是主线程真实的 thr_…，而主线程在模型里没有 ID：不是子线程的父 ID 都归到主线程。
  const childIds = new Set(
    model.threads.flatMap((t) => (t.lane === 0 || t.threadId === null ? [] : [t.threadId])),
  );
  return model.threads.map((thread) => {
    if (thread.lane === 0 || thread.threadId === null) {
      return { id: MAIN_THREAD_KEY, parentId: null, label: '主线程', status: sessionStatus };
    }
    const failed = thread.status === 'idle' && thread.stopReason !== null && thread.stopReason !== 'end_turn';
    return {
      id: thread.threadId,
      parentId:
        thread.parentThreadId !== null && childIds.has(thread.parentThreadId)
          ? thread.parentThreadId
          : MAIN_THREAD_KEY,
      label: thread.agentName ?? '子线程',
      status: thread.status === 'running' ? 'running' : failed ? 'failure' : 'idle',
      ...(failed && thread.stopReason ? { note: STOP_REASON_LABEL[thread.stopReason] } : {}),
    };
  });
}
