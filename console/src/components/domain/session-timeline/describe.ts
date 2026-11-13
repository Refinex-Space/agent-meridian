/**
 * 每一行显示的文字：标题、主体（工具名、模型名等）、一行预览，以及右侧对齐的度量（每项自带单位，不用中点拼接）。
 * 文案按 frontend-design.md「文案」：按用户理解的概念命名，术语与架构设计一致（会话、线程、轮次、待处理动作）。
 */
import type { ContentBlock, EventOf, SessionEvent } from '@/lib/api/types';
import { between, formatBytes, formatDuration, formatInteger, formatTokens } from '@/lib/format';

import { STOP_REASON_LABEL } from '../status-badge/labels';

import type { NodeTone, TimelineModel, TimelineRow } from './model';

export interface Metric {
  /** 显示的文字，例如"1.6 秒""输入 3.2K"。 */
  text: string;
  /** 悬停时的精确值，例如"输入 3,240 token"。 */
  title?: string;
}

export interface RowText {
  title: string;
  subject: { text: string; mono: boolean } | null;
  detail: { text: string; mono: boolean } | null;
  /** 结果或决定，例如"已批准""出错"；带语气。 */
  state: { text: string; tone: NodeTone } | null;
  metrics: Metric[];
}

const RESCHEDULE_LABEL = {
  lease_expired: '租约过期',
  node_shutdown: '节点下线',
  retryable_error: '可重试的错误',
};
const RETRY_LABEL = { retryable: '将重新执行', exhausted: '重试次数已用尽', terminal: '不可重试' };

/** 输出是机器文本的工具：结果用等宽字体。 */
const MACHINE_OUTPUT_TOOLS = new Set(['bash', 'read_file', 'list_files', 'glob_files', 'grep_files']);

export interface DescribeContext {
  model: TimelineModel;
  /** 模型 ID 到显示名；缺少时显示 ID。 */
  modelNames?: Readonly<Record<string, string>>;
}

function contentText(blocks: readonly ContentBlock[]): string {
  return blocks
    .map((block) => {
      if (block.type === 'text') return block.text;
      if (block.type === 'file') return `附件 ${block.filename ?? block.file_id}`;
      return '图片';
    })
    .join(' ');
}

function inputPreview(name: string, input: Record<string, unknown>): { text: string; mono: boolean } | null {
  if (typeof input['command'] === 'string') return { text: input['command'], mono: true };
  if (typeof input['path'] === 'string') return { text: input['path'], mono: true };
  if (typeof input['query'] === 'string') return { text: input['query'], mono: false };
  if (name === 'spawn_subagent' && typeof input['task'] === 'string')
    return { text: input['task'], mono: false };
  const entries = Object.entries(input);
  if (entries.length === 0) return null;
  return { text: entries.map(([k, v]) => `${k}: ${JSON.stringify(v)}`).join(', '), mono: true };
}

function tokenMetrics(usage: {
  input_tokens: number;
  output_tokens: number;
  cached_tokens: number;
}): Metric[] {
  return [
    {
      text: `输入 ${formatTokens(usage.input_tokens)}`,
      title: `输入 ${formatInteger(usage.input_tokens)} token，其中缓存命中 ${formatInteger(usage.cached_tokens)}`,
    },
    {
      text: `输出 ${formatTokens(usage.output_tokens)}`,
      title: `输出 ${formatInteger(usage.output_tokens)} token`,
    },
  ];
}

function toolLabel(name: string): string {
  const mcp = /^mcp__(.+?)__(.+)$/.exec(name);
  return mcp ? `${mcp[1]}/${mcp[2]}` : name;
}

function describeEvent(event: SessionEvent, row: TimelineRow, ctx: DescribeContext): RowText {
  const base: RowText = { title: '', subject: null, detail: null, state: null, metrics: [] };
  const { model } = ctx;
  switch (event.type) {
    case 'user.message': {
      return {
        ...base,
        title: '用户消息',
        detail: { text: contentText(event.data.content), mono: false },
        state: event.data.delivery === 'steer' ? { text: '注入当前轮', tone: 'muted' } : null,
      };
    }
    case 'user.interrupt':
      return { ...base, title: '中断请求' };
    case 'user.tool_confirmation': {
      const action = model.actions.get(event.data.action_id);
      const remember =
        event.data.remember_scope === 'none'
          ? null
          : `记住到${event.data.remember_scope === 'session' ? '本会话' : '本项目'}${event.data.remember_rule ? ` ${event.data.remember_rule}` : ''}`;
      return {
        ...base,
        title: event.data.approved ? '批准' : '拒绝',
        subject: action ? { text: toolLabel(action.summary.tool_name), mono: true } : null,
        detail: event.data.deny_reason ? { text: event.data.deny_reason, mono: false } : null,
        state: remember ? { text: remember, tone: 'muted' } : null,
      };
    }
    case 'user.custom_tool_result': {
      const action = model.actions.get(event.data.action_id);
      return {
        ...base,
        title: '回传结果',
        subject: action ? { text: action.summary.tool_name, mono: true } : null,
        detail: { text: contentText(event.data.content), mono: false },
        state: event.data.error ? { text: '错误结果', tone: 'danger' } : null,
      };
    }
    case 'user.recovery_confirmation': {
      const action = model.actions.get(event.data.action_id);
      return {
        ...base,
        title: event.data.decision === 'retry' ? '重试' : '跳过',
        subject: action ? { text: action.summary.tool_name, mono: true } : null,
      };
    }
    case 'agent.message':
      return { ...base, title: '回复', detail: { text: contentText(event.data.content), mono: false } };
    case 'agent.thinking':
      return {
        ...base,
        title: '思考',
        detail: { text: event.data.text ?? '未暴露思考内容', mono: false },
      };
    case 'agent.tool_use':
    case 'agent.mcp_tool_use':
    case 'agent.custom_tool_use': {
      const input = event.data.input;
      if (event.data.name === 'spawn_subagent') {
        return {
          ...base,
          title: '拉起子智能体',
          subject: typeof input['subagent'] === 'string' ? { text: input['subagent'], mono: true } : null,
          detail: inputPreview(event.data.name, input),
        };
      }
      return {
        ...base,
        title: event.type === 'agent.custom_tool_use' ? '调用自定义工具' : '调用',
        subject: { text: toolLabel(event.data.name), mono: true },
        detail: inputPreview(event.data.name, input),
        metrics:
          event.type === 'agent.mcp_tool_use'
            ? [{ text: 'MCP', title: `MCP 服务 ${event.data.server_name}` }]
            : [],
      };
    }
    case 'agent.tool_result':
    case 'agent.mcp_tool_result': {
      const use = model.toolUses.get(event.data.tool_use_id);
      const metrics: Metric[] = [];
      if (use)
        metrics.push({
          text: formatDuration(between(use.created_at, event.created_at)),
          title: '工具执行耗时',
        });
      const stateText = {
        success: null,
        error: '出错',
        interrupted: '被中断',
        denied: '被拒绝',
        running: '转入后台',
      }[event.data.state];
      const decision = event.data.permission === 'approved' ? '已批准' : null;
      const detail = event.data.content_ref
        ? { text: `结果存入对象存储（${formatBytes(event.data.content_ref.size)}）`, mono: false }
        : { text: contentText(event.data.content), mono: MACHINE_OUTPUT_TOOLS.has(event.data.name) };
      return {
        ...base,
        title: '结果',
        subject: { text: toolLabel(event.data.name), mono: true },
        detail,
        state: stateText
          ? { text: stateText, tone: event.data.state === 'running' ? 'accent' : 'danger' }
          : decision
            ? { text: decision, tone: 'muted' }
            : null,
        metrics,
      };
    }
    case 'agent.context_compacted':
      return {
        ...base,
        title: '上下文已压缩',
        detail: {
          text: `${event.data.messages_before} 条消息压缩为 ${event.data.messages_after} 条`,
          mono: false,
        },
      };
    case 'span.model_request_end': {
      const start = model.modelStarts.get(event.data.request_id);
      const metrics: Metric[] = [];
      if (start)
        metrics.push({
          text: formatDuration(between(start.created_at, event.created_at)),
          title: '模型请求耗时',
        });
      if (event.data.usage) metrics.push(...tokenMetrics(event.data.usage));
      return {
        ...base,
        title: '推理',
        subject: { text: ctx.modelNames?.[event.data.model_id] ?? event.data.model_id, mono: false },
        state: event.data.usage ? null : { text: '未报告用量', tone: 'muted' },
        metrics,
      };
    }
    case 'session.status_running':
      return {
        ...base,
        title: `第 ${row.turnNo ?? 1} 轮`,
        state: row.rerun ? { text: '重新执行', tone: 'reschedule' } : null,
      };
    case 'session.status_idle': {
      const metrics: Metric[] = [];
      const start = event.turn_id ? model.turnStarts.get(event.turn_id) : undefined;
      if (start)
        metrics.push({ text: `用时 ${formatDuration(between(start.created_at, event.created_at))}` });
      const usage = event.turn_id ? model.usageByTurn.get(event.turn_id) : undefined;
      if (usage) metrics.push(...tokenMetrics(usage.data.usage));
      const reason = event.data.stop_reason;
      return {
        ...base,
        title: '本轮结束',
        state: { text: STOP_REASON_LABEL[reason], tone: reason === 'end_turn' ? 'muted' : 'danger' },
        metrics,
      };
    }
    case 'session.status_requires_action': {
      const actions = event.data.actions;
      const first = actions[0];
      const infos = actions.map((a) => model.actions.get(a.id));
      const pending = infos.some((info) => info?.pending);
      const resolved = infos.every((info) => info?.response);
      const state = pending
        ? null
        : resolved
          ? { text: resolutionLabel(infos[0]?.response ?? null), tone: 'muted' as const }
          : { text: '已失效', tone: 'muted' as const };
      if (!first || actions.length > 1) {
        return { ...base, title: `等待处理 ${actions.length} 项`, state };
      }
      const title = {
        tool_confirmation: '等待批准',
        plan_exit: '等待确认退出计划模式',
        custom_tool_result: '等待回传结果',
        recovery_confirmation: '等待确认是否重试',
      }[first.type];
      return {
        ...base,
        title,
        subject: first.type === 'plan_exit' ? null : { text: toolLabel(first.tool_name), mono: true },
        detail: inputPreview(first.tool_name, first.input),
        state,
      };
    }
    case 'session.status_rescheduled':
      return {
        ...base,
        title: '重新调度',
        state: { text: RESCHEDULE_LABEL[event.data.reason], tone: 'reschedule' },
      };
    case 'session.status_terminated':
      return { ...base, title: event.data.reason === 'archived' ? '会话已归档' : '会话已删除' };
    case 'session.error':
      return {
        ...base,
        title: '出错',
        subject: { text: event.data.code, mono: true },
        detail: { text: event.data.message, mono: false },
        state: {
          text: RETRY_LABEL[event.data.retry_status],
          tone: event.data.retry_status === 'retryable' ? 'muted' : 'danger',
        },
      };
    case 'session.thread_created':
      return { ...base, title: '子线程', subject: { text: event.data.agent_name, mono: true } };
    case 'session.thread_status_idle': {
      const thread = model.threads.find((t) => t.threadId === event.data.thread_id);
      const reason = event.data.stop_reason;
      return {
        ...base,
        title: '子线程结束',
        subject: thread?.agentName ? { text: thread.agentName, mono: true } : null,
        state: { text: STOP_REASON_LABEL[reason], tone: reason === 'end_turn' ? 'muted' : 'danger' },
      };
    }
    default:
      return { ...base, title: event.type, subject: null };
  }
}

function resolutionLabel(
  response: EventOf<
    'user.tool_confirmation' | 'user.custom_tool_result' | 'user.recovery_confirmation'
  > | null,
): string {
  if (!response) return '已处理';
  switch (response.type) {
    case 'user.tool_confirmation':
      return response.data.approved ? '已批准' : '已拒绝';
    case 'user.custom_tool_result':
      return '已回传';
    case 'user.recovery_confirmation':
      return response.data.decision === 'retry' ? '已重试' : '已跳过';
  }
}

/** 一行的文字。间隔行只有时长。 */
export function describeRow(row: TimelineRow, ctx: DescribeContext): RowText {
  if (row.kind === 'gap' || !row.event) {
    return {
      title: `间隔 ${formatDuration(row.gapMs ?? 0)}`,
      subject: null,
      detail: null,
      state: null,
      metrics: [],
    };
  }
  return describeEvent(row.event, row, ctx);
}
