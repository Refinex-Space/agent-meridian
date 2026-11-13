import { useState, type ReactNode } from 'react';

import { describeRow, type ActionInfo, type TimelineModel } from '@/components/domain/session-timeline';
import { Button } from '@/components/ui/button';
import { CheckIcon, CopyIcon, SidebarSimpleIcon } from '@/components/ui/glyphs';
import { Icon } from '@/components/ui/icon';
import { ScrollArea } from '@/components/ui/scroll-area';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import type { ContentBlock, SessionEvent } from '@/lib/api/types';
import { between, formatDuration, formatInstant, formatInteger } from '@/lib/format';

export type RememberScope = 'none' | 'session' | 'project';

export interface InspectorProps {
  event: SessionEvent | null;
  model: TimelineModel;
  modelNames: Readonly<Record<string, string>>;
  respondingActionId: string | null;
  onRespond: (
    actionId: string,
    approved: boolean,
    remember: { scope: RememberScope; rule: string | null },
  ) => void;
  onCollapse: () => void;
}

function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="grid grid-cols-[72px_minmax(0,1fr)] gap-3 py-1 text-13 leading-5">
      <dt className="text-ink-2">{label}</dt>
      <dd className="min-w-0 break-words">{children}</dd>
    </div>
  );
}

function Code({ value }: { value: string }) {
  return (
    <pre className="overflow-x-auto rounded-panel bg-surface-inset p-3 font-mono text-12 leading-5 whitespace-pre-wrap text-ink">
      {value}
    </pre>
  );
}

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="flex flex-col gap-2">
      <h3 className="text-13 font-semibold">{title}</h3>
      {children}
    </section>
  );
}

function blocksText(blocks: readonly ContentBlock[]): string {
  return blocks
    .map((b) => (b.type === 'text' ? b.text : b.type === 'file' ? `附件 ${b.filename ?? b.file_id}` : '图片'))
    .join('\n');
}

const PART_LABEL = {
  system_prompt: '系统提示词',
  skills: '技能清单',
  history: '历史消息',
  tool_results: '工具结果',
  injected: '平台注入',
} as const;

function threadLabel(model: TimelineModel, threadId: string | null): string {
  const thread = model.threads.find((t) => t.threadId === threadId);
  if (!thread || thread.lane === 0) return '主线程';
  return thread.agentName ?? '子线程';
}

function Approval({
  action,
  responding,
  onRespond,
}: {
  action: ActionInfo;
  responding: boolean;
  onRespond: InspectorProps['onRespond'];
}) {
  const [scope, setScope] = useState<RememberScope>('none');
  const rules = action.summary.suggested_rules ?? [];
  const [rule, setRule] = useState<string | null>(rules[0] ?? null);
  return (
    <Section title="批准这次调用">
      <fieldset className="flex flex-col gap-2 text-13">
        <legend className="pb-1 text-ink-2">记住此决定</legend>
        {(
          [
            ['none', '只这一次'],
            ['session', '本会话内不再询问'],
            ['project', '本项目内不再询问'],
          ] as const
        ).map(([value, label]) => (
          <label key={value} className="flex items-center gap-2">
            <input
              type="radio"
              name={`remember-${action.summary.id}`}
              value={value}
              checked={scope === value}
              onChange={() => setScope(value)}
              className="size-4 accent-accent"
            />
            {label}
          </label>
        ))}
        {scope !== 'none' && rules.length > 0 && (
          <label className="flex flex-col gap-1 pt-1">
            <span className="text-ink-2">记住的规则</span>
            <select
              value={rule ?? ''}
              onChange={(e) => setRule(e.target.value)}
              className="h-8 rounded-control border border-rule-strong bg-surface px-2 font-mono text-12"
            >
              {rules.map((r) => (
                <option key={r} value={r}>
                  {action.summary.tool_name}({r})
                </option>
              ))}
            </select>
          </label>
        )}
      </fieldset>
      <div className="flex gap-2 pt-1">
        <Button
          variant="primary"
          size="sm"
          pending={responding}
          onClick={() => onRespond(action.summary.id, true, { scope, rule: scope === 'none' ? null : rule })}
        >
          批准
        </Button>
        <Button
          size="sm"
          disabled={responding}
          onClick={() => onRespond(action.summary.id, false, { scope, rule: scope === 'none' ? null : rule })}
        >
          拒绝
        </Button>
      </div>
    </Section>
  );
}

function Details({
  event,
  model,
  modelNames,
  respondingActionId,
  onRespond,
}: Omit<InspectorProps, 'onCollapse'> & { event: SessionEvent }) {
  switch (event.type) {
    case 'user.message':
    case 'agent.message':
      return (
        <Section title="内容">
          <p className="text-14 leading-body whitespace-pre-wrap">{blocksText(event.data.content)}</p>
        </Section>
      );
    case 'agent.thinking':
      return (
        <Section title="思考内容">
          <p className="text-14 leading-body whitespace-pre-wrap text-ink-2">
            {event.data.text ?? '智能体配置不暴露思考内容'}
          </p>
        </Section>
      );
    case 'agent.tool_use':
    case 'agent.mcp_tool_use':
    case 'agent.custom_tool_use':
      return (
        <Section title="入参">
          <Code value={JSON.stringify(event.data.input, null, 2)} />
        </Section>
      );
    case 'agent.tool_result':
    case 'agent.mcp_tool_result': {
      const use = model.toolUses.get(event.data.tool_use_id);
      const permission = { allowed: '规则放行', approved: '人工批准', denied: '被拒绝' }[
        event.data.permission
      ];
      return (
        <>
          <dl>
            <Field label="权限">{permission}</Field>
            {use && <Field label="入参">{<Code value={JSON.stringify(use.data.input, null, 2)} />}</Field>}
          </dl>
          <Section title="结果">
            <Code
              value={
                event.data.content_ref
                  ? `对象存储 ${event.data.content_ref.key}`
                  : blocksText(event.data.content)
              }
            />
          </Section>
        </>
      );
    }
    case 'span.model_request_end': {
      const start = model.modelStarts.get(event.data.request_id);
      const usage = event.data.usage;
      return (
        <>
          {usage && (
            <Section title="用量">
              <dl>
                {(
                  [
                    ['输入', usage.input_tokens],
                    ['缓存命中', usage.cached_tokens],
                    ['输出', usage.output_tokens],
                    ['推理', usage.reasoning_tokens],
                  ] as const
                ).map(([label, value]) => (
                  <Field key={label} label={label}>
                    <span className="tabular-nums">{formatInteger(value)} token</span>
                  </Field>
                ))}
              </dl>
            </Section>
          )}
          {start?.data.context && (
            <Section title="上下文构成">
              <table className="w-full text-13 leading-table">
                <thead>
                  <tr className="border-b border-rule text-left text-12 text-ink-2">
                    <th className="py-1 font-medium">来源</th>
                    <th className="py-1 text-right font-medium">条数</th>
                    <th className="py-1 text-right font-medium">估算 token</th>
                  </tr>
                </thead>
                <tbody>
                  {(Object.keys(PART_LABEL) as (keyof typeof PART_LABEL)[]).map((key) => {
                    const part = start.data.context?.[key];
                    return (
                      <tr key={key} className="border-b border-rule">
                        <td className="py-1">{PART_LABEL[key]}</td>
                        <td className="py-1 text-right tabular-nums">{part?.count ?? 0}</td>
                        <td className="py-1 text-right tabular-nums">{formatInteger(part?.tokens ?? 0)}</td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </Section>
          )}
          <dl>
            <Field label="模型">{modelNames[event.data.model_id] ?? event.data.model_id}</Field>
          </dl>
        </>
      );
    }
    case 'session.status_requires_action': {
      const pending = event.data.actions.map((a) => model.actions.get(a.id)).filter((a) => a?.pending);
      return (
        <>
          {event.data.actions.map((action) => (
            <Section key={action.id} title={`${action.tool_name} 的入参`}>
              <Code value={JSON.stringify(action.input, null, 2)} />
            </Section>
          ))}
          {pending.map(
            (action) =>
              action && (
                <Approval
                  key={action.summary.id}
                  action={action}
                  responding={respondingActionId === action.summary.id}
                  onRespond={onRespond}
                />
              ),
          )}
        </>
      );
    }
    case 'session.error':
      return (
        <Section title="错误">
          <p className="text-14 leading-body">{event.data.message}</p>
        </Section>
      );
    default:
      return null;
  }
}

function CopyJson({ value }: { value: string }) {
  const [copied, setCopied] = useState(false);
  return (
    <Button
      size="sm"
      variant="ghost"
      icon={<Icon glyph={copied ? CheckIcon : CopyIcon} className={copied ? 'text-success' : undefined} />}
      onClick={() => {
        void navigator.clipboard?.writeText(value).then(() => {
          setCopied(true);
          window.setTimeout(() => setCopied(false), 1500);
        });
      }}
    >
      {copied ? '已复制' : '复制'}
    </Button>
  );
}

/** 右栏检查器：选中事件的完整内容、原始 JSON（T20b 的 InspectorPanel 落实正式组件）。 */
export function Inspector(props: InspectorProps) {
  const { event, model, onCollapse } = props;
  const row = event ? model.rows[model.rowIndex.get(event.id) ?? -1] : undefined;
  const text = row ? describeRow(row, { model, modelNames: props.modelNames }) : null;
  const durationFrom =
    event?.type === 'agent.tool_result' || event?.type === 'agent.mcp_tool_result'
      ? model.toolUses.get(event.data.tool_use_id)?.created_at
      : event?.type === 'span.model_request_end'
        ? model.modelStarts.get(event.data.request_id)?.created_at
        : undefined;
  const turnNo = event?.turn_id
    ? model.rows.find((r) => r.kind === 'turn' && r.event?.turn_id === event.turn_id)?.turnNo
    : undefined;

  return (
    <div className="flex h-full flex-col">
      <div className="flex h-12 shrink-0 items-center gap-2 border-b border-rule pr-2 pl-4">
        <h2 className="min-w-0 flex-1 truncate text-14 font-semibold">
          {text ? `${text.title}${text.subject ? ` ${text.subject.text}` : ''}` : '检查器'}
        </h2>
        <Tooltip>
          <TooltipTrigger asChild>
            <Button size="icon-sm" variant="ghost" aria-label="收起检查器" onClick={onCollapse}>
              <Icon glyph={SidebarSimpleIcon} />
            </Button>
          </TooltipTrigger>
          <TooltipContent>收起检查器</TooltipContent>
        </Tooltip>
      </div>
      {event === null ? (
        <p className="p-4 text-13 text-ink-2">选择时间轴上的事件，查看完整内容与原始 JSON。</p>
      ) : (
        <Tabs defaultValue="summary" className="min-h-0 flex-1">
          <TabsList className="px-4">
            <TabsTrigger value="summary">概要</TabsTrigger>
            <TabsTrigger value="json">原始 JSON</TabsTrigger>
          </TabsList>
          <TabsContent value="summary" className="min-h-0">
            <ScrollArea className="h-full">
              <div className="flex flex-col gap-6 p-4">
                <dl>
                  <Field label="类型">
                    <code className="text-12">{event.type}</code>
                  </Field>
                  <Field label="时间">
                    <span className="tabular-nums">{formatInstant(event.created_at)}</span>
                  </Field>
                  <Field label="序号">
                    <span className="tabular-nums">{event.seq}</span>
                  </Field>
                  <Field label="线程">{threadLabel(model, event.thread_id)}</Field>
                  {turnNo !== undefined && turnNo !== null && <Field label="轮次">第 {turnNo} 轮</Field>}
                  {durationFrom && (
                    <Field label="耗时">{formatDuration(between(durationFrom, event.created_at))}</Field>
                  )}
                </dl>
                <Details {...props} event={event} />
              </div>
            </ScrollArea>
          </TabsContent>
          <TabsContent value="json" className="min-h-0">
            <ScrollArea className="h-full">
              <div className="flex flex-col gap-2 p-4">
                <div className="flex justify-end">
                  <CopyJson value={JSON.stringify(event, null, 2)} />
                </div>
                <Code value={JSON.stringify(event, null, 2)} />
              </div>
            </ScrollArea>
          </TabsContent>
        </Tabs>
      )}
    </div>
  );
}
