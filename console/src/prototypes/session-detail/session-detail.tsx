import { AnimatePresence, motion, useReducedMotion } from 'motion/react';
import { useEffect, useMemo, useState } from 'react';

import {
  buildTimeline,
  SessionTimeline,
  SessionTimelineSkeleton,
} from '@/components/domain/session-timeline';
import { Button } from '@/components/ui/button';
import { SidebarSimpleIcon } from '@/components/ui/glyphs';
import { Icon } from '@/components/ui/icon';
import { Skeleton } from '@/components/ui/skeleton';
import { ApiError, getAgent, sendEvents } from '@/lib/api/sessions';
import type { Schemas } from '@/lib/api/types';
import { useSessionEvents } from '@/lib/sse/use-session-events';
import { useDelayedVisible } from '@/lib/use-delayed-visible';

import { Inspector, type RememberScope } from './inspector';
import { SessionHeader } from './session-header';
import { ThreadPanel } from './thread-panel';

export interface SessionDetailProps {
  sessionId: string;
  /** 模型 ID 到显示名（正式页面从模型目录读取）。 */
  modelNames: Readonly<Record<string, string>>;
}

function ProblemNotice({ error, title }: { error: Error; title: string }) {
  const problem = error instanceof ApiError ? error.problem : null;
  return (
    <div role="alert" className="flex flex-col gap-1 border-b border-rule bg-danger-weak px-6 py-3 text-13">
      <span className="font-medium text-danger">{title}</span>
      <span className="text-ink">{problem?.detail ?? error.message}</span>
      {problem && (
        <span className="text-12 text-ink-2">
          请求 ID <code className="text-ink">{problem.request_id}</code>
        </span>
      )}
    </div>
  );
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
  const [responding, setResponding] = useState<string | null>(null);
  const [respondError, setRespondError] = useState<Error | null>(null);
  // 检查器在选中事件后展开，没有选中时把宽度留给时间轴。
  const [inspectorOpen, setInspectorOpen] = useState(false);
  const reduceMotion = useReducedMotion();
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

  const respond = (
    actionId: string,
    approved: boolean,
    remember?: { scope: RememberScope; rule: string | null },
  ) => {
    setResponding(actionId);
    setRespondError(null);
    sendEvents(sessionId, {
      events: [
        {
          type: 'user.tool_confirmation',
          action_id: actionId,
          approved,
          ...(remember && remember.scope !== 'none'
            ? { remember_scope: remember.scope, ...(remember.rule ? { remember_rule: remember.rule } : {}) }
            : {}),
        },
      ],
    })
      .catch((e: unknown) => setRespondError(e instanceof Error ? e : new Error(String(e))))
      .finally(() => setResponding(null));
  };

  if (error) {
    const problem = error instanceof ApiError ? error.problem : null;
    return (
      <div className="flex flex-col items-start gap-3 p-8">
        <h1 className="text-18 font-semibold">无法打开这个会话</h1>
        <p className="max-w-xl text-14 text-ink-2">{problem?.detail ?? error.message}</p>
        {problem && (
          <p className="text-12 text-ink-2">
            请求 ID <code className="text-ink">{problem.request_id}</code>
          </p>
        )}
        <Button onClick={() => window.location.reload()}>重新加载</Button>
      </div>
    );
  }

  return (
    <div className="flex h-full min-h-0 flex-col">
      {session && status ? (
        <SessionHeader session={session} agent={agent} status={status} stream={stream} events={events} />
      ) : (
        showSkeleton && <HeaderSkeleton />
      )}
      {respondError && <ProblemNotice error={respondError} title="回应没有发送成功" />}
      <div className="flex min-h-0 flex-1">
        <aside aria-label="线程与待处理动作" className="w-52 shrink-0 border-r border-rule bg-canvas">
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
        </aside>
        <section aria-label="事件时间线" className="relative min-w-0 flex-1 bg-surface">
          {session && status ? (
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
              onRespond={(actionId, approved) => respond(actionId, approved)}
              respondingActionId={responding}
            />
          ) : (
            showSkeleton && <SessionTimelineSkeleton className="h-full" />
          )}
          {session && !inspectorOpen && (
            <Button
              size="icon-sm"
              variant="secondary"
              aria-label="展开检查器"
              className="absolute top-3 right-4"
              onClick={() => setInspectorOpen(true)}
            >
              <Icon glyph={SidebarSimpleIcon} />
            </Button>
          )}
        </section>
        <AnimatePresence initial={false}>
          {inspectorOpen && (
            <motion.aside
              aria-label="检查器"
              className="shrink-0 overflow-hidden border-l border-rule bg-surface"
              initial={reduceMotion ? { opacity: 0 } : { width: 0, opacity: 0 }}
              animate={reduceMotion ? { opacity: 1, width: 344 } : { width: 344, opacity: 1 }}
              exit={reduceMotion ? { opacity: 0 } : { width: 0, opacity: 0 }}
              transition={{ duration: 0.25, ease: [0.2, 0, 0, 1] }}
            >
              <div className="h-full w-86">
                <Inspector
                  event={selected}
                  model={model}
                  modelNames={modelNames}
                  respondingActionId={responding}
                  onRespond={respond}
                  onCollapse={() => setInspectorOpen(false)}
                />
              </div>
            </motion.aside>
          )}
        </AnimatePresence>
      </div>
    </div>
  );
}
