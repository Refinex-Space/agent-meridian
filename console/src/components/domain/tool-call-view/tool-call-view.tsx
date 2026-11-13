import { useState, type ReactNode } from 'react';

import { Button } from '@/components/ui/button';
import type { ContentBlock, EventOf, PermissionOutcome } from '@/lib/api/types';
import { cn } from '@/lib/cn';
import { between, formatBytes, formatDuration } from '@/lib/format';

import { CodeBlock } from '../code-block';
import { StatusBadge } from '../status-badge';

type ToolUse = EventOf<'agent.tool_use' | 'agent.mcp_tool_use' | 'agent.custom_tool_use'>;
type ToolResult = EventOf<'agent.tool_result' | 'agent.mcp_tool_result'>;

export interface ToolCallViewProps {
  use: ToolUse;
  /** 工具结果；还没有结果时不传。 */
  result?: ToolResult | null;
  /** 调用正在等人批准（会话停在 requires_action，动作仍在等待）。 */
  awaiting?: boolean;
  /** 等待批准时的操作（批准、拒绝），由调用方按权限给出。 */
  actions?: ReactNode;
  /** 结果在对象存储中时按需加载；给出时显示"加载结果"。 */
  onLoadContent?: () => void;
  /** 加载对象存储中的结果进行中。 */
  loadingContent?: boolean;
  className?: string;
}

export const PERMISSION_LABEL: Record<PermissionOutcome, string> = {
  allowed: '规则放行',
  approved: '人工批准',
  denied: '被拒绝',
};

/** 输出是机器文本的工具，结果按纯文本等宽显示；其余工具的结果是给人读的文字。 */
const MACHINE_OUTPUT_TOOLS = new Set(['bash', 'read_file', 'list_files', 'glob_files', 'grep_files']);

function toolLabel(name: string): string {
  const mcp = /^mcp__(.+?)__(.+)$/.exec(name);
  return mcp ? `${mcp[1]}/${mcp[2]}` : name;
}

function blocksText(blocks: readonly ContentBlock[]): string {
  return blocks
    .map((b) => (b.type === 'text' ? b.text : b.type === 'file' ? `附件 ${b.filename ?? b.file_id}` : '图片'))
    .join('\n');
}

function looksLikeJson(text: string): boolean {
  const t = text.trim();
  if (!(t.startsWith('{') || t.startsWith('['))) return false;
  try {
    JSON.parse(t);
    return true;
  } catch {
    return false;
  }
}

const PROSE_COLLAPSE_LINES = 8;

/** 给人读的结果（检索摘要、网页内容）：正文字体，超过 8 行折叠，可展开。机器文本与 JSON 用 CodeBlock。 */
function ProseResult({ text }: { text: string }) {
  const [expanded, setExpanded] = useState(false);
  const long = text.split('\n').length > PROSE_COLLAPSE_LINES || text.length > 480;
  return (
    <div className="flex flex-col gap-1">
      <p
        className={cn(
          'text-13 leading-body break-words whitespace-pre-wrap text-ink',
          long && !expanded && 'line-clamp-8',
        )}
      >
        {text || <span className="text-ink-2">空</span>}
      </p>
      {long && (
        <Button variant="ghost" size="sm" className="-ml-2 self-start" onClick={() => setExpanded((v) => !v)}>
          {expanded ? '收起' : '展开全部'}
        </Button>
      )}
    </div>
  );
}

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="flex flex-col gap-2">
      <h4 className="text-13 font-semibold">{title}</h4>
      {children}
    </section>
  );
}

/**
 * 工具调用：工具名、状态、耗时与权限结论，入参（JSON 高亮），结果（机器文本与 JSON 用等宽，给人读的文字用正文字体；
 * 长内容折叠，超过 16 KB 的结果显示对象存储引用、按需加载）；
 * 等待批准时显示调用方给出的批准、拒绝操作。
 */
export function ToolCallView({
  use,
  result = null,
  awaiting = false,
  actions,
  onLoadContent,
  loadingContent = false,
  className,
}: ToolCallViewProps) {
  const status = awaiting ? 'awaiting' : (result?.data.state ?? 'running');
  const output = result ? blocksText(result.data.content) : '';
  const machine = MACHINE_OUTPUT_TOOLS.has(use.data.name);
  const json = !machine && looksLikeJson(output);
  return (
    <div data-slot="tool-call-view" className={cn('flex flex-col gap-4', className)}>
      <div className="flex flex-wrap items-center gap-x-4 gap-y-1">
        <span className="font-mono text-13 font-medium">{toolLabel(use.data.name)}</span>
        <StatusBadge of="tool" status={status} />
        {result && (
          <span className="text-12 text-ink-2 tabular-nums">
            耗时 {formatDuration(between(use.created_at, result.created_at))}
          </span>
        )}
        {result && (
          <span className={cn('text-12', result.data.permission === 'denied' ? 'text-danger' : 'text-ink-2')}>
            {PERMISSION_LABEL[result.data.permission]}
          </span>
        )}
      </div>

      <Section title="入参">
        <CodeBlock
          code={JSON.stringify(use.data.input, null, 2)}
          language="json"
          label="入参"
          collapseAfter={12}
        />
      </Section>

      {awaiting ? (
        <div className="flex flex-col gap-3 rounded-panel border border-brass/50 bg-brass-weak p-3">
          <p className="text-13 text-brass-ink">批准后执行；拒绝时智能体会收到拒绝结果。</p>
          {actions && <div className="flex gap-2">{actions}</div>}
        </div>
      ) : result === null ? (
        <p className="text-13 text-ink-2">执行中，结果出来后显示在这里。</p>
      ) : result.data.content_ref ? (
        <Section title="结果">
          <div className="flex flex-wrap items-center gap-3 rounded-panel bg-surface-inset p-3 text-13">
            <span className="text-ink-2">
              结果较大（{formatBytes(result.data.content_ref.size)}，{result.data.content_ref.content_type}
              ），保存在对象存储
            </span>
            {onLoadContent && (
              <Button size="sm" pending={loadingContent} onClick={onLoadContent}>
                加载结果
              </Button>
            )}
          </div>
        </Section>
      ) : (
        <Section title="结果">
          {machine || json ? (
            <CodeBlock code={output} language={json ? 'json' : 'text'} label="结果" collapseAfter={16} />
          ) : (
            <ProseResult text={output} />
          )}
        </Section>
      )}
    </div>
  );
}
