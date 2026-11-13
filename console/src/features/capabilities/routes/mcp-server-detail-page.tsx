import { useQuery, useSuspenseQuery } from '@tanstack/react-query';
import { useNavigate } from '@tanstack/react-router';
import { useMemo, useState, type ReactNode } from 'react';

import { CopyableId } from '@/components/domain/copyable-id';
import { DangerConfirmDialog } from '@/components/domain/danger-confirm-dialog';
import { DataRegion } from '@/components/domain/data-region';
import { ErrorState } from '@/components/domain/error-state';
import { KeyValueList } from '@/components/domain/key-value-list';
import { PermissionGate } from '@/components/domain/permission-gate';
import { RelativeTime } from '@/components/domain/relative-time';
import { connectivityStatus, StatusBadge } from '@/components/domain/status-badge';
import { DetailPage } from '@/components/templates';
import { Button } from '@/components/ui/button';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { DotsThreeIcon, InfoIcon } from '@/components/ui/glyphs';
import { Icon } from '@/components/ui/icon';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Skeleton } from '@/components/ui/skeleton';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import { secretsQuery } from '@/features/settings';
import { usePermission, useProject } from '@/lib/access';
import { ApiError } from '@/lib/api/errors';
import type { McpServer, SideEffect } from '@/lib/api/types';
import { useCrumb } from '@/lib/crumbs';
import { formatDuration } from '@/lib/format';

import { useDeleteMcpServer, useProbeMcpServer, useUpdateMcpServer, mcpServerQuery } from '../api/queries';
import { McpServerDialog } from '../components/mcp-server-dialog';
import {
  SIDE_EFFECT_HINT,
  SIDE_EFFECT_LABEL,
  SIDE_EFFECTS,
  toMcpForm,
  toMcpRequest,
  TRANSPORT_LABEL,
} from '../model/mcp';

export interface McpServerDetailPageProps {
  mcpServerId: string;
}

const FOLLOW = '__follow__';

function Section({
  id,
  title,
  aside,
  children,
}: {
  id: string;
  title: ReactNode;
  aside?: ReactNode;
  children: ReactNode;
}) {
  return (
    <section aria-labelledby={id} className="flex flex-col gap-3">
      <div className="flex items-center gap-1">
        <h2 id={id} className="text-16 font-semibold">
          {title}
        </h2>
        {aside}
      </div>
      {children}
    </section>
  );
}

/** 请求头注入的凭据：能查看凭据时写名称，否则写 ID。 */
function useSecretNames(): Map<string, string> {
  const { tenantId } = useProject();
  const read = usePermission('secret_read', '查看凭据');
  const secrets = useQuery({ ...secretsQuery(tenantId), enabled: read.allowed });
  return useMemo(() => new Map((secrets.data ?? []).map((s) => [s.id, s.name])), [secrets.data]);
}

/**
 * 工具清单与逐个工具的副作用等级：每行一个选择器（跟随服务的默认等级，或单独标注），有改动时表格下方出现保存栏。保存整体替换
 * 服务的配置，服务端随之重新探测。
 */
function Tools({ server, editable }: { server: McpServer; editable: boolean }) {
  const update = useUpdateMcpServer(server.id);
  const [draft, setDraft] = useState<Record<string, SideEffect>>(server.tool_side_effects);
  const changed = server.tools.filter(
    (t) => (draft[t.name] ?? null) !== (server.tool_side_effects[t.name] ?? null),
  ).length;
  const fallback = SIDE_EFFECT_LABEL[server.side_effect ?? 'non_idempotent'];

  const save = () => {
    // 只保存仍在清单里的工具（服务端拒绝不存在的工具）。
    const kept = Object.fromEntries(
      Object.entries(draft).filter(([tool]) => server.tools.some((t) => t.name === tool)),
    );
    // 保存成功后服务的 updated_at 变化，整个表随 key 重新挂载、草稿取新值。
    update.mutate(toMcpRequest(toMcpForm(server), kept));
  };

  if (server.tools.length === 0) return <p className="text-13 text-ink-2">这个服务没有提供工具。</p>;
  return (
    <div className="flex flex-col gap-3">
      <Table aria-label="工具清单">
        <TableHeader>
          <TableRow>
            <TableHead className="w-64">工具</TableHead>
            <TableHead>说明</TableHead>
            <TableHead className="w-60">副作用等级</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {server.tools.map((tool) => {
            const own = draft[tool.name];
            return (
              <TableRow key={tool.name}>
                <TableCell className="align-top">
                  <span className="font-mono text-12 font-medium">{tool.name}</span>
                </TableCell>
                <TableCell className="align-top">
                  <span className="line-clamp-2 text-ink-2">{tool.description || '没有说明'}</span>
                </TableCell>
                <TableCell className="align-top">
                  {editable ? (
                    <Select
                      value={own ?? FOLLOW}
                      onValueChange={(v) =>
                        setDraft((d) => {
                          const next = { ...d };
                          if (v === FOLLOW) delete next[tool.name];
                          else next[tool.name] = v as SideEffect;
                          return next;
                        })
                      }
                    >
                      <SelectTrigger size="sm" aria-label={`${tool.name} 的副作用等级`}>
                        <SelectValue />
                      </SelectTrigger>
                      <SelectContent>
                        <SelectItem value={FOLLOW}>跟随默认（{fallback}）</SelectItem>
                        {SIDE_EFFECTS.map((level) => (
                          <SelectItem key={level} value={level}>
                            {SIDE_EFFECT_LABEL[level]}
                          </SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                  ) : (
                    <span>{own ? SIDE_EFFECT_LABEL[own] : `跟随默认（${fallback}）`}</span>
                  )}
                </TableCell>
              </TableRow>
            );
          })}
        </TableBody>
      </Table>
      {update.error ? <ErrorState size="inline" title="标注没有保存" error={update.error} /> : null}
      {editable && changed > 0 && (
        <div role="status" className="flex items-center justify-end gap-3 border-t border-rule pt-3">
          <span className="mr-auto text-13 text-ink-2">有 {changed} 个工具的标注未保存</span>
          <Button
            disabled={update.isPending}
            onClick={() => {
              setDraft(server.tool_side_effects);
              update.reset();
            }}
          >
            放弃
          </Button>
          <Button variant="primary" pending={update.isPending} onClick={save}>
            保存标注
          </Button>
        </div>
      )}
    </div>
  );
}

function Loaded({ mcpServerId }: McpServerDetailPageProps) {
  const { tenantId, projectId } = useProject();
  const navigate = useNavigate();
  const server = useSuspenseQuery(mcpServerQuery({ tenantId, projectId }, mcpServerId)).data;
  const write = usePermission('agent_write', '修改或删除 MCP 服务');
  const secretNames = useSecretNames();
  const probe = useProbeMcpServer();
  const remove = useDeleteMcpServer();
  const [editing, setEditing] = useState(false);
  const [deleting, setDeleting] = useState(false);
  useCrumb(server.name);

  const status = connectivityStatus(server.last_check);
  const check = server.last_check;
  const headers = Object.entries(server.header_secret_ids);
  const probeFailed =
    probe.error instanceof ApiError && probe.error.problem?.code === 'mcp_server.probe_failed';

  return (
    <DetailPage
      title={server.name}
      meta={
        <>
          <StatusBadge of="custom" kind={status.kind} label={status.label} />
          <CopyableId id={server.id} label="MCP 服务 ID" />
          {check && (
            <span className="text-13 text-ink-2">
              探测于 <RelativeTime value={check.checked_at} />
            </span>
          )}
        </>
      }
      actions={
        <>
          <PermissionGate mode="disable" allowed={write.allowed} reason={write.reason}>
            <Button pending={probe.isPending} onClick={() => probe.mutate(server.id)}>
              重新探测
            </Button>
          </PermissionGate>
          <PermissionGate mode="disable" allowed={write.allowed} reason={write.reason}>
            <Button variant="primary" onClick={() => setEditing(true)}>
              修改
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
                  删除 MCP 服务
                </DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenu>
          )}
        </>
      }
    >
      <div className="flex max-w-260 flex-col gap-8 pt-6">
        {probe.error && !probeFailed ? (
          <ErrorState size="inline" title="探测失败" error={probe.error} />
        ) : null}
        <KeyValueList
          columns={2}
          items={[
            { label: '地址', value: server.url, mono: true },
            { label: '传输', value: TRANSPORT_LABEL[server.transport] },
            {
              label: '连接状态',
              value: check ? (
                <span className="flex flex-col gap-1">
                  <span>
                    {status.label}
                    {check.status === 'ok' && (
                      <span className="ml-2 text-ink-2 tabular-nums">{formatDuration(check.latency_ms)}</span>
                    )}
                  </span>
                  {check.message && <span className="text-12 text-danger">{check.message}</span>}
                </span>
              ) : (
                '登记后还没有再探测过'
              ),
            },
            {
              label: '请求头',
              value:
                headers.length === 0 ? (
                  '不注入'
                ) : (
                  <span className="flex flex-col gap-1">
                    {headers.map(([name, secretId]) => (
                      <span key={name}>
                        <span className="font-mono text-12">{name}</span>
                        <span className="ml-2 text-ink-2">{secretNames.get(secretId) ?? secretId}</span>
                      </span>
                    ))}
                  </span>
                ),
            },
            {
              label: '默认副作用等级',
              value: server.side_effect ? SIDE_EFFECT_LABEL[server.side_effect] : '不设置（按有副作用）',
            },
            { label: '工具清单更新于', value: <RelativeTime value={server.probed_at} /> },
          ]}
        />
        <Section
          id="mcp-tools"
          title={`工具（${server.tools.length}）`}
          aside={
            <Tooltip>
              <TooltipTrigger asChild>
                <button
                  type="button"
                  aria-label="副作用等级的说明"
                  className="inline-flex rounded-control text-ink-2 hover:text-ink"
                >
                  <Icon glyph={InfoIcon} />
                </button>
              </TooltipTrigger>
              <TooltipContent className="max-w-96">{SIDE_EFFECT_HINT}</TooltipContent>
            </Tooltip>
          }
        >
          <Tools key={server.updated_at} server={server} editable={write.allowed} />
        </Section>
      </div>
      {editing && <McpServerDialog open onOpenChange={setEditing} server={server} />}
      <DangerConfirmDialog
        open={deleting}
        onOpenChange={setDeleting}
        title={`删除 MCP 服务 ${server.name}`}
        confirmLabel="删除 MCP 服务"
        confirmName={server.name}
        consequences={
          <>
            <p>删除后不能再在智能体里挂载它；被已发布的智能体版本引用时不能删除。</p>
            <p>已经在运行的会话继续使用发布时的配置。</p>
          </>
        }
        onConfirm={async () => {
          await remove.mutateAsync(server.id);
          void navigate({
            to: '/projects/$projectId/capabilities',
            params: { projectId },
            search: { tab: 'mcp-servers' },
          });
        }}
      />
    </DetailPage>
  );
}

/**
 * MCP 服务详情（详情页模板）：连接状态、地址与请求头、工具清单与逐个工具的副作用等级（info 提示解释三个等级与对恢复的影响）。
 * 操作是重新探测、修改与删除。
 */
export function McpServerDetailPage({ mcpServerId }: McpServerDetailPageProps) {
  const read = usePermission('agent_read', '查看 MCP 服务');
  if (!read.allowed)
    return (
      <DetailPage title="MCP 服务">
        <PermissionGate mode="explain" title="没有权限查看 MCP 服务" allowed={false} reason={read.reason} />
      </DetailPage>
    );
  return (
    <DataRegion
      errorTitle="无法打开这个 MCP 服务"
      skeleton={
        <DetailPage title="MCP 服务" loading>
          <div className="flex flex-col gap-3 pt-6">
            {[0, 1, 2, 3].map((i) => (
              <Skeleton key={i} className="h-5 w-full max-w-160" />
            ))}
          </div>
        </DetailPage>
      }
    >
      <Loaded mcpServerId={mcpServerId} />
    </DataRegion>
  );
}
