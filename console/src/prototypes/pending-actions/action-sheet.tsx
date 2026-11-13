import { ApprovalForm, type ApprovalDecision } from '@/components/domain/approval-form';
import { CodeBlock } from '@/components/domain/code-block';
import { CopyableId } from '@/components/domain/copyable-id';
import { InspectorSection } from '@/components/domain/inspector-panel';
import { KeyValueList } from '@/components/domain/key-value-list';
import { PermissionGate } from '@/components/domain/permission-gate';
import { StatusBadge } from '@/components/domain/status-badge';
import { Button } from '@/components/ui/button';
import { LockSimpleIcon } from '@/components/ui/glyphs';
import { Icon } from '@/components/ui/icon';
import {
  Sheet,
  SheetBody,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
} from '@/components/ui/sheet';
import type { PendingAction, Schemas } from '@/lib/api/types';
import { formatInstant, formatRelative } from '@/lib/format';

import { ACTION_TYPE_LABEL, ON_TIMEOUT, toolLabel } from './describe';

export interface ActionResponse {
  approved?: boolean;
  decision?: Schemas['RecoveryDecision'];
  rememberScope?: Schemas['RememberScope'];
  rememberRule?: string | null;
}

export interface ActionSheetProps {
  action: PendingAction | null;
  agentName: string;
  /** 处理人的显示名（`decided_by` 是用户标识或 Key ID，正式页面从成员与 API Key 接口取名字）。 */
  deciderName: string | null;
  now: number;
  /** 正在提交的回应。 */
  pending: 'approve' | 'deny' | 'retry' | 'skip' | null;
  /** 刚在本页回应过的结论（列表刷新之前就地显示）。 */
  responded: Schemas['PendingActionConclusion'] | null;
  deniedReason?: string;
  onRespond: (response: ActionResponse) => void;
  onOpenChange: (open: boolean) => void;
}

/** 恢复确认：节点下线时这次调用执行到一半，结果未知。 */
function RecoveryControls({
  pending,
  deniedReason,
  onRespond,
}: Pick<ActionSheetProps, 'pending' | 'deniedReason' | 'onRespond'>) {
  const allowed = deniedReason === undefined;
  return (
    <InspectorSection title="重试还是跳过">
      <p className="text-13 leading-body text-ink">
        执行这次调用时节点下线，不确定它是否已经生效。重试会用原来的幂等键再执行一次；跳过则告诉智能体这次调用没有执行。
      </p>
      <div className="flex gap-2 pt-1">
        <PermissionGate allowed={allowed} mode="disable" reason={deniedReason ?? ''}>
          <Button
            variant="primary"
            size="sm"
            pending={pending === 'retry'}
            disabled={pending === 'skip'}
            onClick={() => onRespond({ decision: 'retry' })}
          >
            重试
          </Button>
        </PermissionGate>
        <PermissionGate allowed={allowed} mode="disable" reason={deniedReason ?? ''}>
          <Button
            size="sm"
            pending={pending === 'skip'}
            disabled={pending === 'retry'}
            onClick={() => onRespond({ decision: 'skip' })}
          >
            跳过
          </Button>
        </PermissionGate>
      </div>
    </InspectorSection>
  );
}

/**
 * 待处理动作的详情抽屉：完整入参、所在会话与截止时间，以及回应（批准或拒绝并可记住此决定、重试或跳过）。
 * 自定义工具的结果由业务系统回传，这里只展示。
 */
export function ActionSheet({
  action,
  agentName,
  deciderName,
  now,
  pending,
  responded,
  deniedReason,
  onRespond,
  onOpenChange,
}: ActionSheetProps) {
  const conclusion = responded ?? action?.conclusion ?? null;
  const waiting = action?.status === 'pending' && responded === null;
  return (
    <Sheet open={action !== null} onOpenChange={onOpenChange}>
      <SheetContent>
        {action && (
          <>
            <SheetHeader>
              <SheetTitle className="font-mono">{toolLabel(action.tool_name)}</SheetTitle>
              <SheetDescription>{ACTION_TYPE_LABEL[action.type]}</SheetDescription>
            </SheetHeader>
            <SheetBody label="动作详情" className="flex flex-col gap-6">
              <KeyValueList
                labelWidth="narrow"
                items={[
                  {
                    label: '状态',
                    value: waiting ? (
                      <span className="flex flex-wrap items-center gap-x-3">
                        <StatusBadge of="action" status="pending" />
                        <span className="text-ink-2 tabular-nums">
                          {formatRelative(action.expires_at, now)}
                          {ON_TIMEOUT[action.type]}
                        </span>
                      </span>
                    ) : (
                      <StatusBadge
                        of="action"
                        status={responded ? 'resolved' : action.status}
                        conclusion={conclusion}
                      />
                    ),
                  },
                  { label: '智能体', value: agentName },
                  { label: '会话', value: <CopyableId id={action.session_id} label="会话 ID" full /> },
                  {
                    label: '发起',
                    value: <span className="tabular-nums">{formatInstant(action.created_at)}</span>,
                  },
                  ...(!waiting && deciderName ? [{ label: '处理人', value: deciderName }] : []),
                  ...(!waiting && conclusion?.deny_reason
                    ? [{ label: '拒绝理由', value: conclusion.deny_reason }]
                    : []),
                ]}
              />
              <InspectorSection title="入参">
                <CodeBlock
                  code={JSON.stringify(action.input, null, 2)}
                  language="json"
                  label="入参"
                  collapseAfter={20}
                />
              </InspectorSection>
              {waiting && deniedReason !== undefined && action.type !== 'custom_tool_result' && (
                // 没有回应权限：不摆一组不能用的控件，直接说明缺少什么。
                <p className="flex items-start gap-2 rounded-panel bg-surface-inset p-3 text-13 leading-5 text-ink">
                  <span className="flex h-5 items-center">
                    <Icon glyph={LockSimpleIcon} className="text-ink-2" />
                  </span>
                  {deniedReason}
                </p>
              )}
              {waiting &&
                deniedReason === undefined &&
                (action.type === 'tool_confirmation' || action.type === 'plan_exit') && (
                  <ApprovalForm
                    actionId={action.id}
                    toolName={action.tool_name}
                    suggestedRules={action.suggested_rules}
                    pending={pending === 'approve' || pending === 'deny' ? pending : null}
                    deniedReason={deniedReason}
                    onDecide={(d: ApprovalDecision) =>
                      onRespond({
                        approved: d.approved,
                        rememberScope: d.rememberScope,
                        rememberRule: d.rememberRule,
                      })
                    }
                  />
                )}
              {waiting && deniedReason === undefined && action.type === 'recovery_confirmation' && (
                <RecoveryControls pending={pending} deniedReason={deniedReason} onRespond={onRespond} />
              )}
              {waiting && action.type === 'custom_tool_result' && (
                <p className="text-13 leading-body text-ink-2">
                  自定义工具由业务系统执行，结果经会话接口回传；到截止时间仍未回传时{ON_TIMEOUT[action.type]}
                  。
                </p>
              )}
            </SheetBody>
          </>
        )}
      </SheetContent>
    </Sheet>
  );
}
