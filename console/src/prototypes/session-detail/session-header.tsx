import { useState } from 'react';

import { SESSION_STATUS_LABEL, StatusGlyph, type StatusKind } from '@/components/domain/status-badge';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { CheckIcon, CopyIcon } from '@/components/ui/glyphs';
import { Icon } from '@/components/ui/icon';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import type { Schemas, Session, SessionEvent, SessionStatus } from '@/lib/api/types';
import { formatInteger, formatTokens } from '@/lib/format';
import type { StreamState } from '@/lib/sse/session-stream';

const STREAM: Record<StreamState, { kind: StatusKind; label: string }> = {
  open: { kind: 'success', label: '实时' },
  connecting: { kind: 'rescheduled', label: '连接中' },
  reconnecting: { kind: 'rescheduled', label: '重连中' },
  closed: { kind: 'terminated', label: '已断开' },
};

/** 中间省略的 ID：保留前缀与末尾，完整值在复制与提示中。 */
function shortId(id: string): string {
  return id.length > 18 ? `${id.slice(0, 10)}…${id.slice(-6)}` : id;
}

function CopySessionId({ id }: { id: string }) {
  const [copied, setCopied] = useState(false);
  const copy = () => {
    void navigator.clipboard?.writeText(id).then(() => {
      setCopied(true);
      window.setTimeout(() => setCopied(false), 1500);
    });
  };
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <button
          type="button"
          onClick={copy}
          className="flex h-6 items-center gap-1 rounded-control px-1 font-mono text-12 text-ink-2 hover:bg-surface-hover hover:text-ink"
        >
          {/* 可访问名称包含可见文字（WCAG 2.5.3）："复制会话 ID" 加可见的缩写 ID。 */}
          <span className="sr-only">复制会话 ID </span>
          {shortId(id)}
          <Icon glyph={copied ? CheckIcon : CopyIcon} className={copied ? 'text-success' : undefined} />
        </button>
      </TooltipTrigger>
      <TooltipContent>{copied ? '已复制' : id}</TooltipContent>
    </Tooltip>
  );
}

export interface SessionHeaderProps {
  session: Session;
  agent: Schemas['Agent'] | null;
  status: SessionStatus;
  stream: StreamState;
  events: readonly SessionEvent[];
}

/** 会话头部：智能体与版本、状态、会话 ID、连接状态、会话累计用量与操作。 */
export function SessionHeader({ session, agent, status, stream, events }: SessionHeaderProps) {
  const usage = events.findLast((e) => e.type === 'session.usage');
  const total = usage?.type === 'session.usage' ? usage.data.session_usage : null;
  const streamState = STREAM[stream];
  const active = status === 'running' || status === 'requires_action' || status === 'rescheduled';

  return (
    <div className="flex h-18 shrink-0 items-center gap-6 border-b border-rule bg-surface px-6">
      <div className="flex min-w-0 flex-1 flex-col gap-1">
        <div className="flex items-center gap-2">
          <h1 className="truncate text-18 leading-heading font-semibold">{agent?.name ?? '会话'}</h1>
          {agent && <Badge>v{agent.current_version.version}</Badge>}
        </div>
        <div className="flex items-center gap-4 text-12 text-ink-2">
          <span className="flex items-center gap-1 text-13 text-ink">
            <StatusGlyph kind={status === 'terminated' ? 'terminated' : status} />
            {SESSION_STATUS_LABEL[status]}
          </span>
          <CopySessionId id={session.id} />
          <span>
            终端用户 <span className="text-ink">{session.end_user_id ?? '无'}</span>
          </span>
          <span>
            环境修订 <span className="text-ink tabular-nums">{session.environment_revision}</span>
          </span>
          <span className="flex items-center gap-1" aria-live="polite">
            <StatusGlyph kind={streamState.kind} />
            {streamState.label}
          </span>
        </div>
      </div>
      {total && (
        <dl className="flex gap-4 text-12 tabular-nums">
          {(
            [
              ['输入', total.input_tokens],
              ['输出', total.output_tokens],
              ['缓存命中', total.cached_tokens],
            ] as const
          ).map(([label, value]) => (
            <div key={label} className="flex flex-col items-end" title={`${formatInteger(value)} token`}>
              <dt className="text-ink-2">{label}</dt>
              <dd className="text-14 font-medium text-ink">{formatTokens(value)}</dd>
            </div>
          ))}
        </dl>
      )}
      <div className="flex items-center gap-2">
        <Button size="sm" disabled={!active}>
          中断
        </Button>
        <Button size="sm" disabled={status === 'terminated'}>
          归档
        </Button>
      </div>
    </div>
  );
}
