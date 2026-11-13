import { useQuery, useSuspenseQuery } from '@tanstack/react-query';

import { DataRegion } from '@/components/domain/data-region';
import { EmptyState } from '@/components/domain/empty-state';
import { ErrorState } from '@/components/domain/error-state';
import { PermissionGate } from '@/components/domain/permission-gate';
import { DetailPage } from '@/components/templates';
import { Button } from '@/components/ui/button';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { CaretDownIcon } from '@/components/ui/glyphs';
import { Icon } from '@/components/ui/icon';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Skeleton } from '@/components/ui/skeleton';
import { usePermission, useProject } from '@/lib/access';
import type { Agent, AgentVersion } from '@/lib/api/types';

import { agentQuery, compareQuery, versionsQuery } from '../api/queries';
import { useOpenDraft } from '../api/use-open-draft';
import { useAgentResources } from '../components/editor/use-resources';
import { SpecDiff } from '../components/spec-diff';
import { describeChanges } from '../model/diff';

export interface AgentComparePageProps {
  agentId: string;
  /** 搜索参数中的基准与目标版本号；缺省时取上一个发布的版本与最新的版本。 */
  base: number | null;
  target: number | null;
  onChange: (next: { base: number; target: number }) => void;
}

function versionLabel(agent: Agent, v: AgentVersion): string {
  if (v.status === 'draft') return `v${v.version}（草稿）`;
  return agent.current_version?.version === v.version ? `v${v.version}（当前）` : `v${v.version}`;
}

function VersionSelect({
  label,
  agent,
  versions,
  value,
  onChange,
}: {
  label: string;
  agent: Agent;
  versions: readonly AgentVersion[];
  value: number;
  onChange: (v: number) => void;
}) {
  return (
    <Select value={String(value)} onValueChange={(v) => onChange(Number(v))}>
      <SelectTrigger aria-label={label} className="w-40">
        <SelectValue />
      </SelectTrigger>
      <SelectContent>
        {[...versions].reverse().map((v) => (
          <SelectItem key={v.version} value={String(v.version)}>
            {versionLabel(agent, v)}
          </SelectItem>
        ))}
      </SelectContent>
    </Select>
  );
}

function DiffSkeleton() {
  return (
    <div aria-hidden className="flex flex-col">
      {[0, 1, 2, 3].map((i) => (
        <div key={i} className="grid grid-cols-[28%_1fr_1fr] gap-4 border-b border-rule py-3">
          <Skeleton className="h-4 w-28" />
          <Skeleton className="h-4 w-40" />
          <Skeleton className="h-4 w-40" />
        </div>
      ))}
    </div>
  );
}

function Diff({ agent, base, target }: { agent: Agent; base: AgentVersion; target: AgentVersion }) {
  const { tenantId, projectId } = useProject();
  const resources = useAgentResources();
  const compare = useQuery(compareQuery({ tenantId, projectId }, agent.id, base.version, target.version));
  if (compare.error)
    return (
      <ErrorState title="无法加载版本差异" error={compare.error} onRetry={() => void compare.refetch()} />
    );
  if (!compare.data) return <DiffSkeleton />;
  return (
    <SpecDiff
      label={`v${base.version} 与 v${target.version} 的差异`}
      baseLabel={versionLabel(agent, base)}
      targetLabel={versionLabel(agent, target)}
      rows={describeChanges(compare.data.changes, base.spec, target.spec, {
        hideSnapshot: base.status === 'draft' || target.status === 'draft',
        names: resources.names,
      })}
    />
  );
}

function Compare({ agentId, base, target, onChange }: AgentComparePageProps) {
  const { tenantId, projectId } = useProject();
  const scope = { tenantId, projectId };
  const agent = useSuspenseQuery(agentQuery(scope, agentId)).data;
  const versions = useSuspenseQuery(versionsQuery(scope, agentId)).data;
  const write = usePermission('agent_write', '创建草稿');
  const drafts = useOpenDraft(agentId);

  const latest = versions.at(-1);
  const targetVersion = versions.find((v) => v.version === target) ?? latest;
  const baseVersion =
    versions.find((v) => v.version === base) ??
    versions.findLast((v) => v.status === 'published' && v.version < (targetVersion?.version ?? 0)) ??
    versions[0];

  return (
    <DetailPage
      title="版本对比"
      meta={<span>{agent.name}</span>}
      actions={
        baseVersion &&
        targetVersion && (
          <>
            <VersionSelect
              label="基准版本"
              agent={agent}
              versions={versions}
              value={baseVersion.version}
              onChange={(v) => onChange({ base: v, target: targetVersion.version })}
            />
            <span aria-hidden className="text-ink-2">
              对比
            </span>
            <VersionSelect
              label="目标版本"
              agent={agent}
              versions={versions}
              value={targetVersion.version}
              onChange={(v) => onChange({ base: baseVersion.version, target: v })}
            />
            {!write.allowed ? (
              <PermissionGate mode="disable" allowed={false} reason={write.reason}>
                <Button variant="primary">创建草稿</Button>
              </PermissionGate>
            ) : (
              <DropdownMenu>
                <DropdownMenuTrigger asChild>
                  <Button variant="primary" pending={drafts.pending}>
                    创建草稿
                    <Icon glyph={CaretDownIcon} />
                  </Button>
                </DropdownMenuTrigger>
                <DropdownMenuContent align="end" className="min-w-48">
                  {[baseVersion, targetVersion]
                    .filter((v, i, all) => all.findIndex((x) => x.version === v.version) === i)
                    .map((v) => (
                      <DropdownMenuItem key={v.version} onSelect={() => void drafts.fromVersion(v)}>
                        基于 v{v.version} 创建草稿
                      </DropdownMenuItem>
                    ))}
                </DropdownMenuContent>
              </DropdownMenu>
            )}
          </>
        )
      }
    >
      <div className="pt-6">
        {!baseVersion || !targetVersion ? (
          <EmptyState variant="first-use" size="inline" title="还没有版本可以对比" />
        ) : baseVersion.version === targetVersion.version ? (
          <EmptyState variant="clear" size="inline" title="选择两个不同的版本" />
        ) : (
          <Diff
            key={`${baseVersion.version}-${targetVersion.version}`}
            agent={agent}
            base={baseVersion}
            target={targetVersion}
          />
        )}
      </div>
    </DetailPage>
  );
}

/**
 * 版本对比（详情页模板，无标签页）：选择基准与目标两个版本，按字段列出差异（T22-A03）；可以基于其中任一版本创建新草稿（回滚为新草稿）。
 * 两个版本号在搜索参数 `base`、`target` 里，可以直接分享链接。
 */
export function AgentComparePage(props: AgentComparePageProps) {
  return (
    <DataRegion errorTitle="无法打开版本对比" skeleton={<DetailPage title="版本对比" loading />}>
      <Compare {...props} />
    </DataRegion>
  );
}
