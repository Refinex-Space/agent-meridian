import { CopyableId } from '@/components/domain/copyable-id';
import { KeyValueList } from '@/components/domain/key-value-list';
import { RelativeTime } from '@/components/domain/relative-time';
import { StatusBadge } from '@/components/domain/status-badge';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { FlaskIcon } from '@/components/ui/glyphs';
import { Icon } from '@/components/ui/icon';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { formatInteger } from '@/lib/format';

import {
  KNOWLEDGE_BASES,
  MCP_SERVERS,
  MODEL_NAME,
  PERMISSION_MODES,
  SKILLS,
  type AgentDefinitionForm,
  type AgentRow,
  type AgentVersionRow,
} from '../fixtures/agent-definition';
import { DetailPage } from '../templates/detail-page';

export interface AgentDetailProps {
  agent: AgentRow;
  /** 当前发布版本的定义。 */
  definition: AgentDefinitionForm;
  versions: readonly AgentVersionRow[];
}

const STATE: Record<AgentVersionRow['state'], { kind: 'success' | 'idle' | 'running'; label: string }> = {
  draft: { kind: 'idle', label: '草稿' },
  current: { kind: 'success', label: '当前版本' },
  previous: { kind: 'idle', label: '历史版本' },
};

function Versions({ versions, limit }: { versions: readonly AgentVersionRow[]; limit?: number }) {
  return (
    <Table aria-label="版本" className="table-fixed">
      <colgroup>
        <col style={{ width: 72 }} />
        <col style={{ width: 128 }} />
        <col />
        <col style={{ width: 104 }} />
        <col style={{ width: 120 }} />
      </colgroup>
      <TableHeader>
        <TableRow className="hover:bg-transparent">
          <TableHead>版本</TableHead>
          <TableHead>状态</TableHead>
          <TableHead>说明</TableHead>
          <TableHead>发布人</TableHead>
          <TableHead>时间</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {versions.slice(0, limit).map((v) => (
          <TableRow key={v.version}>
            <TableCell className="tabular-nums">v{v.version}</TableCell>
            <TableCell>
              <StatusBadge of="custom" kind={STATE[v.state].kind} label={STATE[v.state].label} />
            </TableCell>
            <TableCell className="truncate">{v.note}</TableCell>
            <TableCell className="truncate">{v.author}</TableCell>
            <TableCell>
              <RelativeTime value={v.at} className="text-ink-2" />
            </TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  );
}

/**
 * 智能体详情（详情页模板的例子）：页头写状态、版本与主操作，标签页分概要、版本、运行情况；概要是两栏属性列表加最近的版本。
 */
export function AgentDetail({ agent, definition: d, versions }: AgentDetailProps) {
  const draft = versions.find((v) => v.state === 'draft');
  const list = (items: string[]) => (items.length ? items.join('、') : null);
  return (
    <DetailPage
      title={agent.name}
      meta={
        <>
          <StatusBadge of="custom" kind="success" label={`已发布 v${agent.version}`} />
          {draft && <Badge tone="accent">v{draft.version} 草稿未发布</Badge>}
          <CopyableId id={agent.id} label="智能体 ID" />
        </>
      }
      actions={
        <>
          <Button icon={<Icon glyph={FlaskIcon} />}>在 Playground 中试用</Button>
          <Button variant="primary">{draft ? `继续编辑 v${draft.version}` : '编辑定义'}</Button>
        </>
      }
      tabs={[
        {
          value: 'summary',
          label: '概要',
          content: (
            <div className="flex flex-col gap-12">
              <KeyValueList
                columns={2}
                items={[
                  { label: '说明', value: agent.description },
                  { label: '主模型', value: MODEL_NAME[d.modelId], mono: true },
                  {
                    label: '备用模型',
                    value: d.fallbackModelId ? MODEL_NAME[d.fallbackModelId] : '跟随模型目录',
                  },
                  {
                    label: '权限模式',
                    value: PERMISSION_MODES.find((m) => m.value === d.permissionMode)?.label,
                    hint: PERMISSION_MODES.find((m) => m.value === d.permissionMode)?.hint,
                  },
                  { label: '平台工具', value: list(d.tools), mono: true },
                  {
                    label: '知识库',
                    value: list(
                      d.knowledgeBaseIds.map((id) => KNOWLEDGE_BASES.find((k) => k.id === id)?.name ?? id),
                    ),
                    hint: d.prefetch ? `每轮前置检索 ${d.prefetchTopK} 个切片` : undefined,
                  },
                  {
                    label: '技能',
                    value: list(
                      d.skills.map(
                        (s) => `${SKILLS.find((k) => k.id === s.skillId)?.name ?? s.skillId} v${s.version}`,
                      ),
                    ),
                    mono: true,
                  },
                  {
                    label: 'MCP 服务',
                    value: list(
                      d.mcpServers.map(
                        (m) => MCP_SERVERS.find((s) => s.id === m.mcpServerId)?.name ?? m.mcpServerId,
                      ),
                    ),
                    mono: true,
                  },
                  { label: '子智能体', value: list(d.subagents.map((s) => s.name)), mono: true },
                  { label: '单轮最大推理', value: `${d.maxIters} 次` },
                ]}
              />
              <section aria-labelledby="recent-versions" className="flex flex-col gap-2">
                <h2 id="recent-versions" className="text-16 font-semibold">
                  最近的版本
                </h2>
                <Versions versions={versions} limit={4} />
              </section>
            </div>
          ),
        },
        { value: 'versions', label: '版本', content: <Versions versions={versions} /> },
        {
          value: 'runs',
          label: '运行情况',
          content: (
            <KeyValueList
              items={[
                { label: '使用中的会话', value: formatInteger(agent.activeSessions) },
                { label: '运行中', value: formatInteger(agent.runningSessions) },
                {
                  label: '24 小时失败率',
                  value: agent.failureRate === null ? '无轮次' : `${(agent.failureRate * 100).toFixed(1)}%`,
                },
              ]}
            />
          ),
        },
      ]}
    />
  );
}
