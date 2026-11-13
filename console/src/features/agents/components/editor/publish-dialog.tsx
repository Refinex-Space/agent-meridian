import { useQuery } from '@tanstack/react-query';

import { ErrorState } from '@/components/domain/error-state';
import { Button } from '@/components/ui/button';
import {
  Dialog,
  DialogBody,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { Skeleton } from '@/components/ui/skeleton';
import { useProject } from '@/lib/access';
import type { Agent } from '@/lib/api/types';
import { formatInteger } from '@/lib/format';

import { agentStatsQuery, compareQuery, versionQuery } from '../../api/queries';
import { describeChanges } from '../../model/diff';
import { SpecDiff } from '../spec-diff';

export interface PublishDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  agent: Agent;
  /** 要发布的草稿版本号。 */
  version: number;
  names: ReadonlyMap<string, string>;
  onConfirm: () => void;
  pending: boolean;
  /** 发布失败且没有字段明细的错误（冲突、服务不可用）。 */
  error: unknown;
}

function DiffSkeleton() {
  return (
    <div aria-hidden className="flex flex-col gap-3">
      {[0, 1, 2].map((i) => (
        <div key={i} className="grid grid-cols-[28%_1fr_1fr] gap-4 border-b border-rule py-3">
          <Skeleton className="h-4 w-24" />
          <Skeleton className="h-4 w-32" />
          <Skeleton className="h-4 w-32" />
        </div>
      ))}
    </div>
  );
}

/**
 * 发布确认：写明对会话的影响（新会话用新版本，进行中的会话不受影响），并列出与当前发布版本的差异（T22-A03）。
 * 第一次发布时没有可比较的版本。
 */
export function PublishDialog({
  open,
  onOpenChange,
  agent,
  version,
  names,
  onConfirm,
  pending,
  error,
}: PublishDialogProps) {
  const { tenantId, projectId } = useProject();
  const scope = { tenantId, projectId };
  const base = agent.current_version?.version ?? null;
  const enabled = open && base !== null;
  const compare = useQuery({ ...compareQuery(scope, agent.id, base ?? 0, version), enabled });
  const baseSpec = useQuery({ ...versionQuery(scope, agent.id, base ?? 0), enabled });
  const target = useQuery({ ...versionQuery(scope, agent.id, version), enabled: open });
  const stats = useQuery({ ...agentStatsQuery(projectId, agent.id), enabled: open && base !== null });
  const active = stats.data?.agents.find((a) => a.agent_id === agent.id)?.active_sessions ?? 0;

  const diffError = compare.error ?? baseSpec.error ?? target.error;
  let diff;
  if (base === null) diff = null;
  else if (diffError)
    diff = (
      <ErrorState
        size="inline"
        title="无法加载版本差异"
        error={diffError}
        onRetry={() => {
          void compare.refetch();
          void baseSpec.refetch();
          void target.refetch();
        }}
      />
    );
  else if (!compare.data || !baseSpec.data || !target.data) diff = <DiffSkeleton />;
  else
    diff = (
      <SpecDiff
        label={`v${base} 与 v${version} 的差异`}
        baseLabel={`v${base}（当前）`}
        targetLabel={`v${version}`}
        rows={describeChanges(compare.data.changes, baseSpec.data.spec, target.data.spec, {
          hideSnapshot: true,
          names,
        })}
      />
    );

  return (
    <Dialog open={open} onOpenChange={(next) => !pending && onOpenChange(next)}>
      <DialogContent size="lg">
        <DialogHeader>
          <DialogTitle>
            发布 {agent.name} v{version}
          </DialogTitle>
          <DialogDescription>
            {base === null
              ? `发布后就能用 ${agent.name} 创建会话；之后的修改要发布新版本才会生效。`
              : `发布后新建的会话使用 v${version}；正在使用 v${base} 的 ${formatInteger(active)} 个会话不受影响。`}
          </DialogDescription>
        </DialogHeader>
        {diff && (
          <DialogBody label="与当前版本的差异" className="flex flex-col gap-2">
            <h3 className="text-13 font-semibold">与 v{base} 的差异</h3>
            {diff}
          </DialogBody>
        )}
        {error ? <ErrorState size="inline" title="发布失败" error={error} /> : null}
        <DialogFooter>
          <Button disabled={pending} onClick={() => onOpenChange(false)}>
            取消
          </Button>
          <Button variant="primary" pending={pending} onClick={onConfirm}>
            发布 v{version}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
