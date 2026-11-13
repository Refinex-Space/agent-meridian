import { useQuery, useSuspenseQuery } from '@tanstack/react-query';
import { Link, useNavigate } from '@tanstack/react-router';
import { lazy, Suspense, useState, type ReactNode } from 'react';

import { CopyableId } from '@/components/domain/copyable-id';
import { DangerConfirmDialog } from '@/components/domain/danger-confirm-dialog';
import { DataRegion } from '@/components/domain/data-region';
import { EmptyState } from '@/components/domain/empty-state';
import { KeyValueList } from '@/components/domain/key-value-list';
import { PermissionGate } from '@/components/domain/permission-gate';
import { RelativeTime } from '@/components/domain/relative-time';
import { StatusBadge } from '@/components/domain/status-badge';
import { DetailPage } from '@/components/templates';
import { Button } from '@/components/ui/button';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { DotsThreeIcon, FlaskIcon } from '@/components/ui/glyphs';
import { Icon } from '@/components/ui/icon';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Skeleton } from '@/components/ui/skeleton';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { meQuery, usePermission, useProject } from '@/lib/access';
import type { Agent, AgentVersion } from '@/lib/api/types';
import { cn } from '@/lib/cn';
import { formatInteger } from '@/lib/format';

import { useDeleteAgent } from '../api/mutations';
import {
  agentQuery,
  agentSessionsQuery,
  agentStatsQuery,
  latestDraft,
  latestPublished,
  versionsQuery,
} from '../api/queries';
import { useOpenDraft } from '../api/use-open-draft';
import { useAgentResources, type AgentResources } from '../components/editor/use-resources';
import { VersionsTable } from '../components/versions-table';
import { PERMISSION_MODE_LABEL } from '../model/labels';
import { durationSeconds, toDraftSpec, type DraftSpec } from '../model/spec';

const SubagentTopology = lazy(() => import('../components/subagent-topology'));

export type AgentDetailTab = 'summary' | 'versions' | 'sessions' | 'subagents';

export interface AgentDetailPageProps {
  agentId: string;
  tab: AgentDetailTab;
  onTabChange: (tab: AgentDetailTab) => void;
}

const list = (items: readonly string[]) => (items.length ? items.join('、') : null);

/** 规格的属性列表（概要）。 */
function summaryItems(agent: Agent, spec: DraftSpec, resources: AgentResources) {
  const name = (id: string) => resources.names.get(id) ?? id;
  const mode = PERMISSION_MODE_LABEL[spec.permissions.mode];
  return [
    { label: '说明', value: agent.description || null },
    { label: '主模型', value: name(spec.model.model_id), mono: true },
    {
      label: '备用模型',
      value: spec.model.fallback_model_id ? name(spec.model.fallback_model_id) : '跟随模型目录',
      mono: spec.model.fallback_model_id !== null,
    },
    { label: '权限模式', value: mode?.label, hint: mode?.hint },
    { label: '平台工具', value: list(spec.tools), mono: true },
    {
      label: 'MCP 服务',
      value: list(spec.mcp_servers.map((m) => name(m.mcp_server_id))),
      mono: true,
    },
    {
      label: '知识库',
      value: list(spec.knowledge.knowledge_base_ids.map(name)),
      hint: spec.knowledge.prefetch ? `每轮前置检索 ${spec.knowledge.prefetch_top_k} 个切片` : undefined,
    },
    { label: '技能', value: list(spec.skills.map((s) => `${name(s.skill_id)} v${s.version}`)), mono: true },
    { label: '子智能体', value: list(spec.subagents.map((s) => s.name)), mono: true },
    {
      label: '权限规则',
      value: spec.permissions.rules.length ? `${spec.permissions.rules.length} 条` : null,
    },
    { label: '单轮最大推理', value: `${spec.max_iters} 次` },
    { label: '计划模式', value: spec.plan_mode ? '开启' : '关闭' },
  ];
}

/** 概要与拓扑用的版本：当前发布的版本，没有发布过时用最新的草稿。 */
function shownVersion(versions: readonly AgentVersion[]): AgentVersion | undefined {
  return latestPublished(versions) ?? latestDraft(versions);
}

function SummaryTab({ agent, canWrite, me }: { agent: Agent; canWrite: boolean; me: string }) {
  const { tenantId, projectId } = useProject();
  const versions = useSuspenseQuery(versionsQuery({ tenantId, projectId }, agent.id)).data;
  const resources = useAgentResources();
  const version = shownVersion(versions);
  if (!version)
    return <EmptyState variant="first-use" size="inline" title="还没有版本：创建草稿，配置好后发布" />;
  const spec = toDraftSpec(version.spec);
  return (
    <div className="flex flex-col gap-12">
      <section aria-labelledby="summary-title" className="flex flex-col gap-4">
        <h2 id="summary-title" className="text-16 font-semibold">
          {version.status === 'published'
            ? `当前版本 v${version.version}`
            : `草稿 v${version.version}（未发布）`}
        </h2>
        <KeyValueList columns={2} items={summaryItems(agent, spec, resources)} />
      </section>
      <section aria-labelledby="recent-versions" className="flex flex-col gap-2">
        <h2 id="recent-versions" className="text-16 font-semibold">
          最近的版本
        </h2>
        <VersionsTable agent={agent} versions={versions} limit={4} canWrite={canWrite} me={me} />
      </section>
    </div>
  );
}

function VersionsTab({ agent, canWrite, me }: { agent: Agent; canWrite: boolean; me: string }) {
  const { tenantId, projectId } = useProject();
  const versions = useSuspenseQuery(versionsQuery({ tenantId, projectId }, agent.id)).data;
  if (versions.length === 0)
    return <EmptyState variant="first-use" size="inline" title="还没有版本：创建草稿，配置好后发布" />;
  return <VersionsTable agent={agent} versions={versions} canWrite={canWrite} me={me} />;
}

function SessionsTab({ agent }: { agent: Agent }) {
  const { tenantId, projectId } = useProject();
  const stats = useSuspenseQuery(agentStatsQuery(projectId, agent.id)).data;
  const sessions = useSuspenseQuery(agentSessionsQuery(projectId, agent.id)).data.data;
  const versions = useQuery(versionsQuery({ tenantId, projectId }, agent.id)).data ?? [];
  const mine = stats.agents.find((a) => a.agent_id === agent.id);
  const versionOf = (id: string) => versions.find((v) => v.id === id)?.version;
  const live = sessions.filter((s) => s.status !== 'terminated' && s.deleted_at === null);
  return (
    <div className="flex flex-col gap-12">
      <KeyValueList
        columns={2}
        items={[
          { label: '使用中的会话', value: formatInteger(mine?.active_sessions ?? 0) },
          { label: '运行中', value: formatInteger(mine?.running_sessions ?? 0) },
          {
            label: '24 小时失败率',
            value:
              mine?.failure_rate === null || mine?.failure_rate === undefined
                ? '无轮次'
                : `${(mine.failure_rate * 100).toFixed(1)}%（${formatInteger(mine.failed_turns)} / ${formatInteger(mine.turns)} 轮）`,
            hint: '失败轮次 ÷ 结束的轮次（完成、中断、失败），按小时汇总',
          },
        ]}
      />
      <section aria-labelledby="sessions-title" className="flex flex-col gap-2">
        <h2 id="sessions-title" className="text-16 font-semibold">
          使用中的会话
        </h2>
        {live.length === 0 ? (
          <EmptyState variant="first-use" size="inline" title="还没有使用这个智能体的会话" />
        ) : (
          <Table aria-label="使用中的会话" className="table-fixed">
            <colgroup>
              <col style={{ width: 240 }} />
              <col style={{ width: 128 }} />
              <col style={{ width: 88 }} />
              <col />
              <col style={{ width: 136 }} />
            </colgroup>
            <TableHeader>
              <TableRow className="hover:bg-transparent">
                <TableHead>会话</TableHead>
                <TableHead>状态</TableHead>
                <TableHead>版本</TableHead>
                <TableHead>终端用户</TableHead>
                <TableHead>更新</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {live.map((s) => {
                const v = versionOf(s.agent_version_id);
                return (
                  <TableRow key={s.id}>
                    <TableCell>
                      <CopyableId id={s.id} label="会话 ID" />
                    </TableCell>
                    <TableCell>
                      <StatusBadge of="session" status={s.status} />
                    </TableCell>
                    <TableCell className="tabular-nums">{v === undefined ? '—' : `v${v}`}</TableCell>
                    <TableCell className="truncate font-mono text-12">{s.end_user_id ?? '—'}</TableCell>
                    <TableCell>
                      <RelativeTime value={s.updated_at} className="text-ink-2" />
                    </TableCell>
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
        )}
      </section>
    </div>
  );
}

function SubagentsTab({ agent }: { agent: Agent }) {
  const { tenantId, projectId } = useProject();
  const versions = useSuspenseQuery(versionsQuery({ tenantId, projectId }, agent.id)).data;
  const resources = useAgentResources();
  const fallback = shownVersion(versions);
  const [chosen, setChosen] = useState<number | null>(null);
  const [highlight, setHighlight] = useState<string | null>(null);
  const version = versions.find((v) => v.version === chosen) ?? fallback;
  if (!version)
    return <EmptyState variant="first-use" size="inline" title="还没有版本：创建草稿，配置好后发布" />;
  const spec = toDraftSpec(version.spec);
  const nameOf = (id: string) => resources.names.get(id) ?? id;
  const main = { name: agent.name, modelName: nameOf(spec.model.model_id), tools: [...spec.tools] };
  const subagents = spec.subagents.map((s) => ({
    name: s.name,
    modelName: nameOf(s.model.model_id),
    tools: s.tools,
  }));
  const select = (name: string) => {
    setHighlight(name);
    document.getElementById(`subagent-def-${name}`)?.scrollIntoView({ block: 'start', behavior: 'smooth' });
  };
  return (
    <div className="flex flex-col gap-6">
      <div className="flex items-center gap-3">
        <span id="topology-version-label" className="text-13 text-ink-2">
          版本
        </span>
        <Select value={String(version.version)} onValueChange={(v) => setChosen(Number(v))}>
          <SelectTrigger aria-labelledby="topology-version-label" className="w-48">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {[...versions].reverse().map((v) => (
              <SelectItem key={v.version} value={String(v.version)}>
                v{v.version}
                {v.status === 'draft'
                  ? '（草稿）'
                  : agent.current_version?.version === v.version
                    ? '（当前）'
                    : ''}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>
      {subagents.length === 0 ? (
        <EmptyState variant="first-use" size="inline" title={`v${version.version} 没有声明子智能体`} />
      ) : (
        <>
          <div className="h-120 rounded-panel border border-rule bg-surface-inset">
            <Suspense fallback={<Skeleton className="size-full" />}>
              <SubagentTopology main={main} subagents={subagents} onSelect={select} />
            </Suspense>
          </div>
          <div className="flex flex-col gap-6">
            {spec.subagents.map((s) => (
              <section
                key={s.name}
                id={`subagent-def-${s.name}`}
                aria-labelledby={`subagent-def-${s.name}-title`}
                className={cn(
                  'flex scroll-mt-6 flex-col gap-3 rounded-panel border border-transparent p-3 transition-colors duration-200',
                  highlight === s.name && 'border-accent bg-accent-weak',
                )}
              >
                <h3 id={`subagent-def-${s.name}-title`} className="font-mono text-13 font-semibold">
                  {s.name}
                </h3>
                <KeyValueList
                  columns={2}
                  items={[
                    { label: '用途', value: s.description || null },
                    { label: '模型', value: nameOf(s.model.model_id), mono: true },
                    { label: '可用工具', value: list(s.tools), mono: true },
                    {
                      label: '技能',
                      value: list(s.skills.map((k) => `${nameOf(k.skill_id)} v${k.version}`)),
                      mono: true,
                    },
                    { label: '最大推理次数', value: `${s.max_iters} 次` },
                    { label: '超时', value: `${durationSeconds(s.timeout)} 秒` },
                    {
                      label: '放行规则',
                      value: s.permissions.rules.length ? `${s.permissions.rules.length} 条` : null,
                    },
                  ]}
                />
              </section>
            ))}
          </div>
        </>
      )}
    </div>
  );
}

function TabSkeleton() {
  return (
    <div aria-hidden className="grid grid-cols-2 gap-x-12 gap-y-3">
      {Array.from({ length: 8 }, (_, i) => (
        <div key={i} className="grid grid-cols-[160px_minmax(0,1fr)] gap-3">
          <Skeleton className="h-4 w-20" />
          <Skeleton className="h-4 w-40" />
        </div>
      ))}
    </div>
  );
}

function Detail({ agentId, tab, onTabChange }: AgentDetailPageProps) {
  const { tenantId, projectId } = useProject();
  const navigate = useNavigate();
  const agent = useSuspenseQuery(agentQuery({ tenantId, projectId }, agentId)).data;
  const me = useSuspenseQuery(meQuery()).data.subject;
  const versions = useQuery(versionsQuery({ tenantId, projectId }, agentId)).data;
  const stats = useQuery(agentStatsQuery(projectId, agentId)).data;
  const write = usePermission('agent_write', '编辑智能体');
  const chat = usePermission('session_write', '在 Playground 中试用');
  const drafts = useOpenDraft(agentId);
  const remove = useDeleteAgent();
  const [deleting, setDeleting] = useState(false);
  const draft = versions ? latestDraft(versions) : undefined;
  const editable = draft && (!agent.current_version || draft.version > agent.current_version.version);
  const active = stats?.agents.find((a) => a.agent_id === agentId)?.active_sessions ?? 0;

  const region = (content: ReactNode, title: string) => (
    <DataRegion errorTitle={title} skeleton={<TabSkeleton />}>
      {content}
    </DataRegion>
  );

  return (
    <>
      <DetailPage
        title={agent.name}
        meta={
          <>
            {agent.current_version ? (
              <StatusBadge of="custom" kind="success" label={`已发布 v${agent.current_version.version}`} />
            ) : (
              <StatusBadge of="custom" kind="idle" label="未发布" />
            )}
            <CopyableId id={agent.id} label="智能体 ID" />
            <span>
              更新于 <RelativeTime value={agent.updated_at} />
            </span>
          </>
        }
        actions={
          <>
            {agent.current_version && (
              <PermissionGate mode="disable" allowed={chat.allowed} reason={chat.reason}>
                <Button asChild>
                  <Link to="/projects/$projectId/playground" params={{ projectId }}>
                    <Icon glyph={FlaskIcon} />在 Playground 中试用
                  </Link>
                </Button>
              </PermissionGate>
            )}
            <PermissionGate mode="disable" allowed={write.allowed} reason={write.reason}>
              <Button variant="primary" pending={drafts.pending} onClick={() => void drafts.open()}>
                {editable ? `继续编辑 v${draft.version}` : '编辑定义'}
              </Button>
            </PermissionGate>
            {write.allowed && (
              <DropdownMenu>
                <DropdownMenuTrigger asChild>
                  <Button size="icon-md" aria-label="更多操作">
                    <Icon glyph={DotsThreeIcon} />
                  </Button>
                </DropdownMenuTrigger>
                <DropdownMenuContent align="end" className="min-w-40">
                  <DropdownMenuItem variant="danger" onSelect={() => setDeleting(true)}>
                    删除智能体
                  </DropdownMenuItem>
                </DropdownMenuContent>
              </DropdownMenu>
            )}
          </>
        }
        tabs={[
          {
            value: 'summary',
            label: '概要',
            content: region(<SummaryTab agent={agent} canWrite={write.allowed} me={me} />, '无法加载版本'),
          },
          {
            value: 'versions',
            label: '版本',
            content: region(<VersionsTab agent={agent} canWrite={write.allowed} me={me} />, '无法加载版本'),
          },
          {
            value: 'sessions',
            label: '会话',
            content: region(<SessionsTab agent={agent} />, '无法加载会话'),
          },
          {
            value: 'subagents',
            label: '子智能体',
            content: region(<SubagentsTab agent={agent} />, '无法加载版本'),
          },
        ]}
        tab={tab}
        onTabChange={(value) => onTabChange(value as AgentDetailTab)}
      />
      <DangerConfirmDialog
        open={deleting}
        onOpenChange={setDeleting}
        title={`删除智能体 ${agent.name}`}
        confirmLabel="删除智能体"
        confirmName={agent.name}
        consequences={
          <>
            <p>删除后不能再用它创建会话，全部版本与草稿随之删除，不能恢复。</p>
            <p>
              正在使用它的 {formatInteger(active)}{' '}
              个会话继续使用已绑定的版本，直到归档或删除；它们的事件仍可查看。
            </p>
          </>
        }
        onConfirm={async () => {
          await remove.mutateAsync(agentId);
          void navigate({ to: '/projects/$projectId/agents', params: { projectId } });
        }}
      />
    </>
  );
}

/**
 * 智能体详情（详情页模板）：页头写状态、ID 与主操作；标签页分概要（当前版本的配置与最近的版本）、版本（对比、基于某版本创建草稿、
 * 删除草稿）、会话（运行情况与使用中的会话）、子智能体（拓扑与各自的定义）。标签页在搜索参数 `tab` 里。
 */
export function AgentDetailPage(props: AgentDetailPageProps) {
  return (
    <DataRegion errorTitle="无法打开这个智能体" skeleton={<DetailPage title="智能体" loading />}>
      <Detail {...props} />
    </DataRegion>
  );
}
