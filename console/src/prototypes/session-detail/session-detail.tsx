import { useEffect, useMemo, useState } from 'react';

import type { ApprovalDecision } from '@/components/domain/approval-form';
import { ErrorState } from '@/components/domain/error-state';
import {
  buildTimeline,
  SessionTimeline,
  SessionTimelineSkeleton,
} from '@/components/domain/session-timeline';
import { Skeleton } from '@/components/ui/skeleton';
import { getAgent, sendEvents } from '@/lib/api/sessions';
import type { Schemas } from '@/lib/api/types';
import { useSessionEvents } from '@/lib/sse/use-session-events';
import { useDelayedVisible } from '@/lib/use-delayed-visible';

import { Workbench } from '../templates/workbench';

import { Inspector } from './inspector';
import { SessionHeader } from './session-header';
import { ThreadPanel } from './thread-panel';

export interface SessionDetailProps {
  sessionId: string;
  /** 模型 ID 到显示名（正式页面从模型目录读取）。 */
  modelNames: Readonly<Record<string, string>>;
}

/** 头部的骨架：与 SessionHeader 同高，标题与元信息两行。 */
function HeaderSkeleton() {
  return (
    <div className="flex h-18 shrink-0 flex-col justify-center gap-2 border-b border-rule bg-surface px-6">
      <Skeleton className="h-5 w-40" />
      <Skeleton className="h-3 w-96" />
    </div>
  );
}

/** 每 30 秒更新一次的当前时间（待处理动作的剩余时间）。 */
function useMinuteClock(): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 30_000);
    return () => window.clearInterval(timer);
  }, []);
  return now;
}

/**
 * 会话详情（工作台模板）原型：左栏线程与待处理动作，中间时间轴，右栏检查器。
 * 数据来自公开 API（原型中由 MSW 模拟）：读会话与历史事件，再订阅 SSE；批准与拒绝经 `POST …/events` 发送回应事件。
 */
export function SessionDetail({ sessionId, modelNames }: SessionDetailProps) {
  const { session, events, live, status, stream, error } = useSessionEvents(sessionId);
  const [agent, setAgent] = useState<Schemas['Agent'] | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [responding, setResponding] = useState<{ actionId: string; approved: boolean } | null>(null);
  const [respondError, setRespondError] = useState<Error | null>(null);
  // 检查器在选中事件后展开，没有选中时把宽度留给时间轴。
  const [inspectorOpen, setInspectorOpen] = useState(false);
  const now = useMinuteClock();
  const loading = session === null && error === null;
  const showSkeleton = useDelayedVisible(loading);

  const agentId = session?.agent_id ?? null;
  useEffect(() => {
    if (!agentId) return undefined;
    const controller = new AbortController();
    getAgent(agentId, { signal: controller.signal })
      .then(setAgent)
      .catch(() => setAgent(null));
    return () => controller.abort();
  }, [agentId]);

  const open = status === 'running' || status === 'requires_action' || status === 'rescheduled';
  const model = useMemo(() => buildTimeline(events, { open }), [events, open]);
  const selected =
    selectedId === null ? null : (model.rows[model.rowIndex.get(selectedId) ?? -1]?.event ?? null);

  const respond = (actionId: string, decision: ApprovalDecision) => {
    setResponding({ actionId, approved: decision.approved });
    setRespondError(null);
    sendEvents(sessionId, {
      events: [
        {
          type: 'user.tool_confirmation',
          action_id: actionId,
          approved: decision.approved,
          ...(decision.rememberScope !== 'none'
            ? {
                remember_scope: decision.rememberScope,
                ...(decision.rememberRule ? { remember_rule: decision.rememberRule } : {}),
              }
            : {}),
        },
      ],
    })
      .catch((e: unknown) => setRespondError(e instanceof Error ? e : new Error(String(e))))
      .finally(() => setResponding(null));
  };

  if (error) {
    return (
      <ErrorState
        title="无法打开这个会话"
        error={error}
        onRetry={() => window.location.reload()}
        className="h-full"
      />
    );
  }

  const pendingActions = [...model.actions.values()].filter((a) => a.pending).length;

  return (
    <Workbench
      header={
        session && status ? (
          <SessionHeader
            session={session}
            agent={agent}
            status={status}
            stream={stream}
            events={events}
            pendingActions={pendingActions}
            onExpandInspector={inspectorOpen ? undefined : () => setInspectorOpen(true)}
          />
        ) : (
          showSkeleton && <HeaderSkeleton />
        )
      }
      notice={
        respondError && (
          <ErrorState
            size="inline"
            title="回应没有发送成功"
            error={respondError}
            className="mx-6 mt-3 rounded-panel"
          />
        )
      }
      left={{
        label: '线程与待处理动作',
        width: { initial: 208, min: 176, max: 320 },
        content: (
          <>
            {!session && showSkeleton && (
              <div aria-hidden className="flex flex-col gap-3 px-3 py-4">
                <Skeleton className="ml-2 h-3 w-10" />
                <Skeleton className="ml-2 h-3 w-24" />
                <Skeleton className="ml-6 h-3 w-28" />
                <Skeleton className="ml-6 h-3 w-24" />
              </div>
            )}
            {session && status && (
              <ThreadPanel
                model={model}
                status={status}
                selectedId={selectedId}
                onJump={setSelectedId}
                now={now}
              />
            )}
          </>
        ),
      }}
      main={{
        label: '事件时间线',
        content:
          session && status ? (
            <SessionTimeline
              className="h-full"
              events={events}
              status={status}
              live={live}
              modelNames={modelNames}
              selectedId={selectedId}
              onSelect={(event) => {
                setSelectedId(event.id);
                setInspectorOpen(true);
              }}
              onRespond={(actionId, approved) =>
                respond(actionId, { approved, rememberScope: 'none', rememberRule: null })
              }
              respondingActionId={responding?.actionId ?? null}
            />
          ) : (
            showSkeleton && <SessionTimelineSkeleton className="h-full" />
          ),
      }}
      right={{
        label: '检查器',
        width: { initial: 344, min: 288, max: 560 },
        open: inspectorOpen,
        content: (
          <Inspector
            event={selected}
            model={model}
            modelNames={modelNames}
            responding={responding}
            onRespond={respond}
            onCollapse={() => setInspectorOpen(false)}
          />
        ),
      }}
    />
  );
}
