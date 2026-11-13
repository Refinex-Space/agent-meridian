import type {
  PendingActionStatus,
  Schemas,
  SessionStatus,
  StopReason,
  ToolResultState,
} from '@/lib/api/types';
import { cn } from '@/lib/cn';

import { actionStatus, sessionStatus, toolStatus, turnStatus, type StatusMeaning } from './labels';
import { StatusGlyph, type StatusKind } from './status-glyph';

/** 按对象给出状态；`custom` 用于表外的对象（文档、投递记录），调用方自己选语义与文字。 */
export type StatusBadgeProps = {
  /** sm 12px 文字（表格内的次要信息），md 13px（默认）。 */
  size?: 'sm' | 'md';
  className?: string;
} & (
  | { of: 'session'; status: SessionStatus }
  | { of: 'turn'; status: 'running' | StopReason }
  | { of: 'tool'; status: ToolResultState | 'awaiting' }
  | { of: 'action'; status: PendingActionStatus; conclusion?: Schemas['PendingActionConclusion'] | null }
  | { of: 'custom'; kind: StatusKind; label: string }
);

function meaningOf(props: StatusBadgeProps): StatusMeaning {
  switch (props.of) {
    case 'session':
      return sessionStatus(props.status);
    case 'turn':
      return turnStatus(props.status);
    case 'tool':
      return toolStatus(props.status);
    case 'action':
      return actionStatus(props.status, props.conclusion);
    case 'custom':
      return { kind: props.kind, label: props.label };
  }
}

/**
 * 状态徽标：状态符号（形状加颜色）加文字，不填底色、不做胶囊。等待处理的文字用黄铜色，其余为墨色，
 * 颜色只是辅助，形状与文字本身就能区分状态（色盲与灰度打印下仍可读）。
 */
export function StatusBadge(props: StatusBadgeProps) {
  const { kind, label } = meaningOf(props);
  return (
    <span
      data-slot="status-badge"
      data-kind={kind}
      className={cn(
        'inline-flex items-center gap-2 whitespace-nowrap',
        props.size === 'sm' ? 'text-12' : 'text-13',
        kind === 'requires_action' ? 'font-medium text-brass-ink' : 'text-ink',
        props.className,
      )}
    >
      <StatusGlyph kind={kind} />
      {label}
    </span>
  );
}
