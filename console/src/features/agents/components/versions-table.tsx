import { Link } from '@tanstack/react-router';
import { useState } from 'react';

import { DangerConfirmDialog } from '@/components/domain/danger-confirm-dialog';
import { RelativeTime } from '@/components/domain/relative-time';
import { StatusBadge } from '@/components/domain/status-badge';
import { Button } from '@/components/ui/button';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { DotsThreeIcon } from '@/components/ui/glyphs';
import { Icon } from '@/components/ui/icon';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { useProject } from '@/lib/access';
import type { Agent, AgentVersion } from '@/lib/api/types';
import { middleEllipsis } from '@/lib/format';

import { useDeleteDraft } from '../api/mutations';
import { useOpenDraft } from '../api/use-open-draft';

export interface VersionsTableProps {
  agent: Agent;
  /** 版本号从小到大（接口的顺序）；表格从新到旧显示。 */
  versions: readonly AgentVersion[];
  /** 只显示最近的几个（概要页）。 */
  limit?: number;
  /** 当前用户能否编辑（AGENT_WRITE）：不能时不显示行操作。 */
  canWrite: boolean;
  /** 当前用户的主体标识：自己创建、发布的版本写"你"。 */
  me: string;
}

function stateOf(agent: Agent, v: AgentVersion): { kind: 'success' | 'idle'; label: string } {
  if (v.status === 'draft') return { kind: 'idle', label: '草稿' };
  return agent.current_version?.version === v.version
    ? { kind: 'success', label: '当前版本' }
    : { kind: 'idle', label: '历史版本' };
}

/**
 * 版本表：版本、状态（当前版本、历史版本、草稿）、发布人或创建人、时间；行操作是与上一版本对比、基于此版本创建草稿、编辑草稿、
 * 删除草稿（删除写明后果）。
 */
export function VersionsTable({ agent, versions, limit, canWrite, me }: VersionsTableProps) {
  const { projectId } = useProject();
  const drafts = useOpenDraft(agent.id);
  const deleteDraft = useDeleteDraft(agent.id);
  const [deleting, setDeleting] = useState<AgentVersion | null>(null);
  const rows = [...versions].reverse().slice(0, limit);
  const who = (subject: string) => (subject === me ? '你' : middleEllipsis(subject, 8, 4));

  return (
    <>
      <Table aria-label={limit ? '最近的版本' : '版本'} className="table-fixed">
        <colgroup>
          <col style={{ width: 72 }} />
          <col style={{ width: 128 }} />
          <col />
          <col style={{ width: 136 }} />
          <col style={{ width: 120 }} />
          <col style={{ width: 56 }} />
        </colgroup>
        <TableHeader>
          <TableRow className="hover:bg-transparent">
            <TableHead>版本</TableHead>
            <TableHead>状态</TableHead>
            <TableHead>对比</TableHead>
            <TableHead>发布或创建</TableHead>
            <TableHead>时间</TableHead>
            <TableHead>
              <span className="sr-only">操作</span>
            </TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {rows.map((v) => {
            const state = stateOf(agent, v);
            const previous = versions.findLast((p) => p.version < v.version && p.status === 'published');
            return (
              <TableRow key={v.version}>
                <TableCell className="tabular-nums">v{v.version}</TableCell>
                <TableCell>
                  <StatusBadge of="custom" kind={state.kind} label={state.label} />
                </TableCell>
                <TableCell className="truncate">
                  {previous ? (
                    <Link
                      to="/projects/$projectId/agents/$agentId/compare"
                      params={{ projectId, agentId: agent.id }}
                      search={{ base: String(previous.version), target: String(v.version) }}
                      className="text-accent hover:underline"
                    >
                      与 v{previous.version} 对比
                    </Link>
                  ) : (
                    <span className="text-ink-2">第一个版本</span>
                  )}
                </TableCell>
                <TableCell className="truncate font-mono text-12 text-ink-2">
                  {who(v.published_by ?? v.created_by)}
                </TableCell>
                <TableCell>
                  <RelativeTime value={v.published_at ?? v.updated_at} className="text-ink-2" />
                </TableCell>
                <TableCell className="text-right">
                  {canWrite && (
                    <DropdownMenu>
                      <DropdownMenuTrigger asChild>
                        <Button size="icon-sm" variant="ghost" aria-label={`v${v.version} 的更多操作`}>
                          <Icon glyph={DotsThreeIcon} />
                        </Button>
                      </DropdownMenuTrigger>
                      <DropdownMenuContent align="end" className="min-w-44">
                        {v.status === 'draft' ? (
                          <DropdownMenuItem asChild>
                            <Link
                              to="/projects/$projectId/agents/$agentId/versions/$version/edit"
                              params={{ projectId, agentId: agent.id, version: String(v.version) }}
                            >
                              编辑草稿
                            </Link>
                          </DropdownMenuItem>
                        ) : null}
                        <DropdownMenuItem onSelect={() => void drafts.fromVersion(v)}>
                          基于 v{v.version} 创建草稿
                        </DropdownMenuItem>
                        {v.status === 'draft' && (
                          <>
                            <DropdownMenuSeparator />
                            <DropdownMenuItem variant="danger" onSelect={() => setDeleting(v)}>
                              删除草稿
                            </DropdownMenuItem>
                          </>
                        )}
                      </DropdownMenuContent>
                    </DropdownMenu>
                  )}
                </TableCell>
              </TableRow>
            );
          })}
        </TableBody>
      </Table>
      <DangerConfirmDialog
        open={deleting !== null}
        onOpenChange={(open) => !open && setDeleting(null)}
        title={`删除草稿 v${deleting?.version ?? ''}`}
        confirmLabel="删除草稿"
        consequences={<p>草稿中还没发布的修改会丢失，不能恢复；已发布的版本与使用它们的会话不受影响。</p>}
        onConfirm={async () => {
          if (deleting) await deleteDraft.mutateAsync(deleting.version);
        }}
      />
    </>
  );
}
