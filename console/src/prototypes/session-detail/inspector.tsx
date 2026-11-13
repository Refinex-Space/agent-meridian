import { useMemo } from 'react';

import { ApprovalForm, type ApprovalDecision } from '@/components/domain/approval-form';
import { InspectorPanel, InspectorSection } from '@/components/domain/inspector-panel';
import { KeyValueList, type KeyValueItem } from '@/components/domain/key-value-list';
import { describeRow, type TimelineModel } from '@/components/domain/session-timeline';
import { ToolCallView } from '@/components/domain/tool-call-view';
import { TokenUsage } from '@/components/domain/token-usage';
import type { ContentBlock, EventOf, SessionEvent } from '@/lib/api/types';
import { between, formatDuration, formatInstant, formatInteger } from '@/lib/format';

export interface InspectorProps {
  event: SessionEvent | null;
  model: TimelineModel;
  modelNames: Readonly<Record<string, string>>;
  /** 正在提交回应的动作与决定。 */
  responding: { actionId: string; approved: boolean } | null;
  onRespond: (actionId: string, decision: ApprovalDecision) => void;
  onCollapse: () => void;
}

type ToolResult = EventOf<'agent.tool_result' | 'agent.mcp_tool_result'>;

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

function Prose({ text, muted = false }: { text: string; muted?: boolean }) {
  return (
    <p
      className={
        muted
          ? 'text-14 leading-body whitespace-pre-wrap text-ink-2'
          : 'text-14 leading-body whitespace-pre-wrap'
      }
    >
      {text}
    </p>
  );
}

function Details({
  event,
  model,
  modelNames,
  results,
  responding,
  onRespond,
}: Omit<InspectorProps, 'onCollapse' | 'event'> & { event: SessionEvent; results: Map<string, ToolResult> }) {
  switch (event.type) {
    case 'user.message':
    case 'agent.message':
      return (
        <InspectorSection title="内容">
          <Prose text={blocksText(event.data.content)} />
        </InspectorSection>
      );
    case 'agent.thinking':
      return (
        <InspectorSection title="思考内容">
          <Prose text={event.data.text ?? '智能体配置不暴露思考内容'} muted />
        </InspectorSection>
      );
    case 'agent.tool_use':
    case 'agent.mcp_tool_use':
    case 'agent.custom_tool_use':
      return <ToolCallView use={event} result={results.get(event.data.tool_use_id) ?? null} />;
    case 'agent.tool_result':
    case 'agent.mcp_tool_result': {
      const use = model.toolUses.get(event.data.tool_use_id);
      return use ? <ToolCallView use={use} result={event} /> : null;
    }
    case 'span.model_request_end': {
      const start = model.modelStarts.get(event.data.request_id);
      return (
        <>
          <KeyValueList
            labelWidth="narrow"
            items={[
              { label: '模型', value: modelNames[event.data.model_id] ?? event.data.model_id, mono: true },
            ]}
          />
          {event.data.usage && (
            <InspectorSection title="用量">
              <TokenUsage usage={event.data.usage} variant="detail" />
            </InspectorSection>
          )}
          {start?.data.context && (
            <InspectorSection title="上下文构成">
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
            </InspectorSection>
          )}
        </>
      );
    }
    case 'session.status_requires_action':
      return (
        <>
          {event.data.actions.map((summary) => {
            const action = model.actions.get(summary.id);
            const use = model.toolUses.get(summary.tool_use_id);
            const waiting = action?.pending ?? false;
            const form = waiting && (
              <ApprovalForm
                actionId={summary.id}
                toolName={summary.tool_name}
                suggestedRules={summary.suggested_rules ?? []}
                pending={
                  responding?.actionId === summary.id ? (responding.approved ? 'approve' : 'deny') : null
                }
                onDecide={(decision) => onRespond(summary.id, decision)}
              />
            );
            return use ? (
              <ToolCallView
                key={summary.id}
                use={use}
                result={results.get(summary.tool_use_id) ?? null}
                awaiting={waiting}
                actions={form}
              />
            ) : (
              <InspectorSection key={summary.id} title={summary.tool_name}>
                {form}
              </InspectorSection>
            );
          })}
        </>
      );
    case 'session.error':
      return (
        <InspectorSection title="错误">
          <Prose text={event.data.message} />
        </InspectorSection>
      );
    default:
      return null;
  }
}

/** 右栏检查器：选中事件的属性、按类型的详情（工具调用、用量与上下文构成、审批）与原始 JSON。 */
export function Inspector(props: InspectorProps) {
  const { event, model, onCollapse } = props;
  // 工具调用到结果：选中调用时同时看到结果。
  const results = useMemo(() => {
    const map = new Map<string, ToolResult>();
    for (const row of model.rows) {
      const e = row.event;
      if (e && (e.type === 'agent.tool_result' || e.type === 'agent.mcp_tool_result'))
        map.set(e.data.tool_use_id, e);
    }
    return map;
  }, [model]);

  if (event === null) {
    return <InspectorPanel onCollapse={onCollapse} empty="选择时间轴上的事件，查看完整内容与原始 JSON。" />;
  }

  const row = model.rows[model.rowIndex.get(event.id) ?? -1];
  const text = row ? describeRow(row, { model, modelNames: props.modelNames }) : null;
  const durationFrom =
    event.type === 'agent.tool_result' || event.type === 'agent.mcp_tool_result'
      ? model.toolUses.get(event.data.tool_use_id)?.created_at
      : event.type === 'span.model_request_end'
        ? model.modelStarts.get(event.data.request_id)?.created_at
        : undefined;
  const turnNo = event.turn_id
    ? model.rows.find((r) => r.kind === 'turn' && r.event?.turn_id === event.turn_id)?.turnNo
    : undefined;

  const items: KeyValueItem[] = [
    { label: '时间', value: <span className="tabular-nums">{formatInstant(event.created_at)}</span> },
    { label: '线程', value: threadLabel(model, event.thread_id) },
    ...(turnNo !== undefined && turnNo !== null ? [{ label: '轮次', value: `第 ${turnNo} 轮` }] : []),
    ...(durationFrom
      ? [{ label: '耗时', value: formatDuration(between(durationFrom, event.created_at)) }]
      : []),
    { label: '类型', value: event.type, mono: true },
    { label: '序号', value: <span className="tabular-nums">{event.seq}</span> },
  ];

  return (
    <InspectorPanel
      title={text ? `${text.title}${text.subject ? ` ${text.subject.text}` : ''}` : event.type}
      raw={event}
      onCollapse={onCollapse}
    >
      <KeyValueList labelWidth="narrow" items={items} />
      <Details {...props} event={event} results={results} />
    </InspectorPanel>
  );
}
