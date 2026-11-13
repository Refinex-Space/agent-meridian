import { useState } from 'react';

import { CopyableId } from '@/components/domain/copyable-id';
import { DangerConfirmDialog } from '@/components/domain/danger-confirm-dialog';
import { StatusBadge } from '@/components/domain/status-badge';
import { StreamIndicator } from '@/components/domain/stream-indicator';
import { TokenUsage } from '@/components/domain/token-usage';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { SidebarSimpleIcon } from '@/components/ui/glyphs';
import { Icon } from '@/components/ui/icon';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import type { Schemas, Session, SessionEvent, SessionStatus } from '@/lib/api/types';
import type { StreamState } from '@/lib/sse/session-stream';

export interface SessionHeaderProps {
  session: Session;
  agent: Schemas['Agent'] | null;
  status: SessionStatus;
  stream: StreamState;
  events: readonly SessionEvent[];
  /** 等待回应的动作数：归档时随之作废，写进后果说明。 */
  pendingActions: number;
  /** 检查器收起时给出：头部最右侧出现"展开检查器"（展开后由检查器自己的标题栏收起）。 */
  onExpandInspector?: () => void;
}

/**
 * 会话头部：智能体与版本、状态、会话 ID、终端用户、环境修订、实时推送状态，右侧是会话累计用量与操作。
 * 归档不可逆，确认对话框写明后果；执行中的会话不能归档（接口返回 409），按钮不可用。
 */
export function SessionHeader({
  session,
  agent,
  status,
  stream,
  events,
  pendingActions,
  onExpandInspector,
}: SessionHeaderProps) {
  const [archiveOpen, setArchiveOpen] = useState(false);
  const usage = events.findLast((e) => e.type === 'session.usage');
  const total = usage?.type === 'session.usage' ? usage.data.session_usage : null;
  const active = status === 'running' || status === 'requires_action' || status === 'rescheduled';
  const archivable = status === 'idle' || status === 'requires_action';

  return (
    <div className="flex h-18 shrink-0 items-center gap-6 border-b border-rule bg-surface px-6">
      <div className="flex min-w-0 flex-1 flex-col gap-1">
        <div className="flex items-center gap-2">
          <h1 className="truncate text-18 leading-heading font-semibold">{agent?.name ?? '会话'}</h1>
          {agent && <Badge>v{agent.current_version.version}</Badge>}
        </div>
        <div className="flex items-center gap-4 text-12 text-ink-2">
          <StatusBadge of="session" status={status} />
          <CopyableId id={session.id} label="会话 ID" />
          <span>
            终端用户 <span className="text-ink">{session.end_user_id ?? '无'}</span>
          </span>
          <span>
            环境修订 <span className="text-ink tabular-nums">{session.environment_revision}</span>
          </span>
          {status !== 'terminated' && <StreamIndicator state={stream} />}
        </div>
      </div>
      {total && <TokenUsage usage={total} />}
      <div className="flex items-center gap-2">
        <Button size="sm" disabled={!active}>
          中断
        </Button>
        <Button size="sm" disabled={!archivable} onClick={() => setArchiveOpen(true)}>
          归档
        </Button>
        {onExpandInspector && (
          <Tooltip>
            <TooltipTrigger asChild>
              <Button size="icon-sm" variant="ghost" aria-label="展开检查器" onClick={onExpandInspector}>
                <Icon glyph={SidebarSimpleIcon} />
              </Button>
            </TooltipTrigger>
            <TooltipContent>展开检查器</TooltipContent>
          </Tooltip>
        )}
      </div>
      <DangerConfirmDialog
        open={archiveOpen}
        onOpenChange={setArchiveOpen}
        title="归档这个会话"
        confirmLabel="归档会话"
        consequences={
          <>
            <p>归档后会话变为已终止，只能查看，不能再发送消息；事件仍可查询与订阅。</p>
            {pendingActions > 0 && (
              <p>等待回应的 {pendingActions} 个动作随之作废，对应的工具调用视为被拒绝。</p>
            )}
          </>
        }
        onConfirm={() => new Promise((resolve) => window.setTimeout(resolve, 600))}
      />
    </div>
  );
}
