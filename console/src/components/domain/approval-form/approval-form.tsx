import { useId, useState } from 'react';

import { Button } from '@/components/ui/button';
import { Label } from '@/components/ui/label';
import { RadioGroup, RadioGroupItem } from '@/components/ui/radio-group';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import type { Schemas } from '@/lib/api/types';
import { cn } from '@/lib/cn';

import { PermissionGate } from '../permission-gate';

type RememberScope = Schemas['RememberScope'];

/** 审批人的决定，对应 `user.tool_confirmation` 的 `approved`、`remember_scope`、`remember_rule`。 */
export interface ApprovalDecision {
  approved: boolean;
  rememberScope: RememberScope;
  /** 选中的建议规则；null 表示按原来的方式记（一般工具记整个工具，bash 记这一条命令）或不记住。 */
  rememberRule: string | null;
}

export interface ApprovalFormProps {
  /** 动作 ID，用来区分同一页上的多个表单。 */
  actionId: string;
  toolName: string;
  /** 动作的 `suggested_rules`：工具按这次的入参给出的内容级规则（命令前缀、路径、域名）。 */
  suggestedRules: readonly string[];
  /** 正在提交的决定：按钮内显示进度，两个按钮都不能再点。 */
  pending?: 'approve' | 'deny' | null;
  onDecide: (decision: ApprovalDecision) => void;
  /** 当前用户没有回应权限时的原因（缺少 `actions:respond`）：按钮保持可聚焦、不可用，并说明原因。 */
  deniedReason?: string;
  className?: string;
}

const SCOPES: readonly (readonly [RememberScope, string])[] = [
  ['none', '只这一次'],
  ['session', '本会话内不再询问'],
  ['project', '本项目内不再询问'],
];

/** "按原来的方式记"在选择器里的取值（Radix Select 不接受空串）。 */
const DEFAULT_RULE = '__default__';

/**
 * 工具调用的审批：批准或拒绝，可选"记住此决定"的作用域；记住时可以从建议规则中选一条（例如只记 `python scripts/` 开头的命令），
 * 不选时按原来的方式记。会话检查器与待处理页共用。
 */
export function ApprovalForm({
  actionId,
  toolName,
  suggestedRules,
  pending = null,
  onDecide,
  deniedReason,
  className,
}: ApprovalFormProps) {
  const id = useId();
  const [scope, setScope] = useState<RememberScope>('none');
  const [rule, setRule] = useState(suggestedRules[0] ?? DEFAULT_RULE);
  const busy = pending !== null;
  const allowed = deniedReason === undefined;

  const decide = (approved: boolean) => {
    if (busy || !allowed) return;
    onDecide({
      approved,
      rememberScope: scope,
      rememberRule: scope === 'none' || rule === DEFAULT_RULE ? null : rule,
    });
  };

  return (
    <div data-slot="approval-form" data-action-id={actionId} className={cn('flex flex-col gap-4', className)}>
      <fieldset className="flex flex-col gap-2" disabled={busy || !allowed}>
        <legend className="pb-2 text-13 font-semibold">记住此决定</legend>
        <RadioGroup value={scope} onValueChange={(v) => setScope(v as RememberScope)} aria-label="记住此决定">
          {SCOPES.map(([value, label]) => (
            <div key={value} className="flex items-center gap-2">
              <RadioGroupItem id={`${id}-${value}`} value={value} />
              <Label htmlFor={`${id}-${value}`} className="font-normal">
                {label}
              </Label>
            </div>
          ))}
        </RadioGroup>
        {scope !== 'none' && (
          <div className="flex flex-col gap-1 pt-2">
            <Label htmlFor={`${id}-rule`}>记住的范围</Label>
            <Select value={rule} onValueChange={setRule}>
              <SelectTrigger id={`${id}-rule`} className="w-full font-mono text-12">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {suggestedRules.map((r) => (
                  <SelectItem key={r} value={r} className="font-mono text-12">
                    {toolName}({r})
                  </SelectItem>
                ))}
                <SelectItem value={DEFAULT_RULE}>
                  {toolName === 'bash' ? '只记这一条命令' : `整个 ${toolName} 工具`}
                </SelectItem>
              </SelectContent>
            </Select>
          </div>
        )}
      </fieldset>
      <div className="flex gap-2">
        <PermissionGate allowed={allowed} mode="disable" reason={deniedReason ?? ''}>
          <Button
            variant="primary"
            size="sm"
            pending={pending === 'approve'}
            disabled={pending === 'deny'}
            onClick={() => decide(true)}
          >
            批准
          </Button>
        </PermissionGate>
        <PermissionGate allowed={allowed} mode="disable" reason={deniedReason ?? ''}>
          <Button
            size="sm"
            pending={pending === 'deny'}
            disabled={pending === 'approve'}
            onClick={() => decide(false)}
          >
            拒绝
          </Button>
        </PermissionGate>
      </div>
    </div>
  );
}
