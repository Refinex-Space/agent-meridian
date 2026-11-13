/**
 * 时间轴的模型：把按 seq 排好的持久化事件换算成行、线（lane）、分叉与并回、轮次主刻度、空闲间隔。纯函数，O(n)。
 *
 * - 主线程与会话级事件在 0 号线（子午线）上；`session.thread_created` 为子线程分配最小的空闲线号，
 *   `session.thread_status_idle` 让子线程并回父线程并释放线号。
 * - 不单独成行的事件：`span.model_request_start`（并入对应的 end，算出耗时）、`session.usage`（并入本轮结束）、
 *   `session.message_consumed`、`session.thread_status_running`。
 * - 相邻两行相隔超过 `gapThresholdMs` 时插入间隔行，子午线在那里断开。
 */
import type { EventOf, PendingActionSummary, SessionEvent } from '@/lib/api/types';

/** 节点符号，形状见 frontend-design.md「设计定稿·时间轴的构成」。 */
export type NodeShape =
  | 'input'
  | 'message'
  | 'thinking'
  | 'tool'
  | 'result'
  | 'measure'
  | 'pending'
  | 'idle'
  | 'reschedule'
  | 'error'
  | 'terminated'
  | 'compacted'
  | 'thread';

/** 节点颜色的语义（映射到令牌在 TimelineGraph 中完成）。 */
export type NodeTone =
  'ink' | 'muted' | 'accent' | 'brass' | 'success' | 'danger' | 'reschedule' | 'terminated';

export interface TimelineRow {
  /** 稳定的键：事件 ID，间隔行为 `gap:<前一个事件 ID>`。 */
  key: string;
  kind: 'event' | 'turn' | 'gap';
  event: SessionEvent | null;
  /** 节点所在的线；间隔行为 -1。 */
  lane: number;
  shape: NodeShape | null;
  tone: NodeTone;
  /** 整行贯穿的其他线（不含节点所在的线）。 */
  through: number[];
  /** 节点所在的线在节点上方、下方是否有线。 */
  above: boolean;
  below: boolean;
  /** 分叉：节点所在的线从哪条父线分出。 */
  forkFrom: number | null;
  /** 并回：节点所在的线在节点之后并入哪条父线。 */
  mergeInto: number | null;
  /** 轮次主刻度的序号（从 1 起）；同一轮被重新执行时序号不变、`rerun` 为 true。 */
  turnNo: number | null;
  rerun: boolean;
  /** 间隔行的时长（毫秒）。 */
  gapMs: number | null;
}

export interface ThreadInfo {
  threadId: string | null;
  agentName: string | null;
  lane: number;
  parentThreadId: string | null;
  parentToolUseId: string | null;
  /** 子线程的状态：拉起后为 running，结束后为 idle（带停止原因）。主线程的状态看会话状态。 */
  status: 'running' | 'idle';
  stopReason: EventOf<'session.thread_status_idle'>['data']['stop_reason'] | null;
  /** 第一行（主线程为 0，子线程为分叉行）的下标，线程树用它定位。 */
  firstRow: number;
}

export interface ActionInfo {
  summary: PendingActionSummary;
  /** 产生动作的 `session.status_requires_action` 事件。 */
  requestedBy: EventOf<'session.status_requires_action'>;
  /** 回应事件；没有回应时为 null。 */
  response: EventOf<
    'user.tool_confirmation' | 'user.custom_tool_result' | 'user.recovery_confirmation'
  > | null;
  /** 仍在等待回应：没有回应，且会话的最新状态仍是这次 requires_action。 */
  pending: boolean;
}

type ToolUseEvent = EventOf<'agent.tool_use' | 'agent.mcp_tool_use' | 'agent.custom_tool_use'>;

export interface TimelineModel {
  rows: TimelineRow[];
  /** 同时存在的线的最大数目（决定图形列的宽度），至少为 1。 */
  laneCount: number;
  threads: ThreadInfo[];
  toolUses: Map<string, ToolUseEvent>;
  modelStarts: Map<string, EventOf<'span.model_request_start'>>;
  usageByTurn: Map<string, EventOf<'session.usage'>>;
  /** 每轮第一次开始运行的事件，用来算本轮用时。 */
  turnStarts: Map<string, EventOf<'session.status_running'>>;
  actions: Map<string, ActionInfo>;
  /** 按事件 ID 找行下标。 */
  rowIndex: Map<string, number>;
  lastEvent: SessionEvent | null;
}

export interface BuildOptions {
  /** 会话仍在进行（运行中、等待处理、重新调度）：最后一行之后子午线继续延伸到"此刻"。 */
  open?: boolean;
  gapThresholdMs?: number;
}

const HIDDEN = new Set<SessionEvent['type']>([
  'span.model_request_start',
  'session.usage',
  'session.message_consumed',
  'session.thread_status_running',
]);

function nodeOf(event: SessionEvent): { shape: NodeShape | null; tone: NodeTone } {
  switch (event.type) {
    case 'user.message':
    case 'user.interrupt':
    case 'user.tool_confirmation':
    case 'user.custom_tool_result':
    case 'user.recovery_confirmation':
      return { shape: 'input', tone: 'ink' };
    case 'agent.message':
      return { shape: 'message', tone: 'ink' };
    case 'agent.thinking':
      return { shape: 'thinking', tone: 'muted' };
    case 'agent.tool_use':
    case 'agent.mcp_tool_use':
    case 'agent.custom_tool_use':
      return { shape: 'tool', tone: 'ink' };
    case 'agent.tool_result':
    case 'agent.mcp_tool_result': {
      const state = event.data.state;
      if (state === 'success') return { shape: 'result', tone: 'success' };
      if (state === 'running') return { shape: 'result', tone: 'accent' };
      return { shape: 'result', tone: 'danger' };
    }
    case 'agent.context_compacted':
      return { shape: 'compacted', tone: 'muted' };
    case 'span.model_request_end':
      return { shape: 'measure', tone: 'muted' };
    case 'session.status_running':
      return { shape: null, tone: 'ink' };
    case 'session.status_idle':
      return { shape: 'idle', tone: event.data.stop_reason === 'error' ? 'danger' : 'muted' };
    case 'session.status_requires_action':
      return { shape: 'pending', tone: 'brass' };
    case 'session.status_rescheduled':
      return { shape: 'reschedule', tone: 'reschedule' };
    case 'session.status_terminated':
      return { shape: 'terminated', tone: 'terminated' };
    case 'session.error':
      return { shape: 'error', tone: 'danger' };
    case 'session.thread_created':
      return { shape: 'thread', tone: 'ink' };
    case 'session.thread_status_idle':
      return { shape: 'idle', tone: event.data.stop_reason === 'end_turn' ? 'muted' : 'danger' };
    default:
      return { shape: 'measure', tone: 'muted' };
  }
}

/** 由事件序列构造时间轴模型。 */
export function buildTimeline(events: readonly SessionEvent[], options: BuildOptions = {}): TimelineModel {
  const gapThreshold = options.gapThresholdMs ?? 60_000;
  const rows: TimelineRow[] = [];
  const rowIndex = new Map<string, number>();
  const toolUses = new Map<string, ToolUseEvent>();
  const modelStarts = new Map<string, EventOf<'span.model_request_start'>>();
  const usageByTurn = new Map<string, EventOf<'session.usage'>>();
  const turnStarts = new Map<string, EventOf<'session.status_running'>>();
  const actions = new Map<string, ActionInfo>();
  const childLane = new Map<string, number>();
  const threads: ThreadInfo[] = [];
  const mainThread: ThreadInfo = {
    threadId: null,
    agentName: null,
    lane: 0,
    parentThreadId: null,
    parentToolUseId: null,
    status: 'running',
    stopReason: null,
    firstRow: 0,
  };
  threads.push(mainThread);
  const threadById = new Map<string, ThreadInfo>();

  /** 被占用的线号（0 号线在第一行之后一直存在）。 */
  const active = new Set<number>();
  let laneCount = 1;
  let turnNo = 0;
  let lastTurnId: string | null = null;
  let previousVisible: SessionEvent | null = null;
  let latestStatus: SessionEvent | null = null;

  function laneOfThread(threadId: string | null): number {
    if (threadId === null) return 0;
    return childLane.get(threadId) ?? 0;
  }

  function allocateLane(): number {
    let lane = 1;
    while (active.has(lane)) lane += 1;
    laneCount = Math.max(laneCount, lane + 1);
    return lane;
  }

  function lanesExcept(lane: number): number[] {
    return [...active].filter((l) => l !== lane).sort((a, b) => a - b);
  }

  for (const event of events) {
    switch (event.type) {
      case 'agent.tool_use':
      case 'agent.mcp_tool_use':
      case 'agent.custom_tool_use':
        toolUses.set(event.data.tool_use_id, event);
        break;
      case 'span.model_request_start':
        modelStarts.set(event.data.request_id, event);
        break;
      case 'session.usage':
        if (event.turn_id) usageByTurn.set(event.turn_id, event);
        break;
      case 'session.status_running':
        if (event.turn_id && !turnStarts.has(event.turn_id)) turnStarts.set(event.turn_id, event);
        break;
      case 'session.status_requires_action':
        for (const summary of event.data.actions) {
          actions.set(summary.id, { summary, requestedBy: event, response: null, pending: false });
        }
        break;
      case 'user.tool_confirmation':
      case 'user.custom_tool_result':
      case 'user.recovery_confirmation': {
        const action = actions.get(event.data.action_id);
        if (action) action.response = event;
        break;
      }
      default:
        break;
    }
    if (event.type.startsWith('session.status_')) latestStatus = event;
    if (mainThread.threadId === null && event.thread_id !== null && !childLane.has(event.thread_id)) {
      if (!threadById.has(event.thread_id)) mainThread.threadId = event.thread_id;
    }

    if (HIDDEN.has(event.type)) continue;

    // 间隔：与上一可见行相隔过久时，子午线断开。
    if (previousVisible) {
      const gap = Date.parse(event.created_at) - Date.parse(previousVisible.created_at);
      if (gap > gapThreshold) {
        rows.push({
          key: `gap:${previousVisible.id}`,
          kind: 'gap',
          event: null,
          lane: -1,
          shape: null,
          tone: 'muted',
          through: [...active].sort((a, b) => a - b),
          above: false,
          below: false,
          forkFrom: null,
          mergeInto: null,
          turnNo: null,
          rerun: false,
          gapMs: gap,
        });
      }
    }

    const { shape, tone } = nodeOf(event);
    const index = rows.length;
    let row: TimelineRow;

    if (event.type === 'session.thread_created') {
      const parentLane = laneOfThread(event.data.parent_thread_id);
      const lane = allocateLane();
      childLane.set(event.data.thread_id, lane);
      const info: ThreadInfo = {
        threadId: event.data.thread_id,
        agentName: event.data.agent_name,
        lane,
        parentThreadId: event.data.parent_thread_id,
        parentToolUseId: event.data.parent_tool_use_id,
        status: 'running',
        stopReason: null,
        firstRow: index,
      };
      threads.push(info);
      threadById.set(event.data.thread_id, info);
      row = {
        key: event.id,
        kind: 'event',
        event,
        lane,
        shape,
        tone,
        through: lanesExcept(lane),
        above: false,
        below: true,
        forkFrom: parentLane,
        mergeInto: null,
        turnNo: null,
        rerun: false,
        gapMs: null,
      };
      active.add(lane);
    } else if (event.type === 'session.thread_status_idle' && childLane.has(event.data.thread_id)) {
      const lane = childLane.get(event.data.thread_id) ?? 0;
      const info = threadById.get(event.data.thread_id);
      if (info) {
        info.status = 'idle';
        info.stopReason = event.data.stop_reason;
      }
      row = {
        key: event.id,
        kind: 'event',
        event,
        lane,
        shape,
        tone,
        through: lanesExcept(lane),
        above: true,
        below: false,
        forkFrom: null,
        mergeInto: laneOfThread(info?.parentThreadId ?? null),
        turnNo: null,
        rerun: false,
        gapMs: null,
      };
      active.delete(lane);
      childLane.delete(event.data.thread_id);
    } else {
      const lane = laneOfThread(event.thread_id);
      const isTurn = event.type === 'session.status_running';
      let rerun = false;
      if (isTurn) {
        if (event.turn_id !== null && event.turn_id === lastTurnId) rerun = true;
        else turnNo += 1;
        lastTurnId = event.turn_id;
      }
      const wasActive = active.has(lane);
      row = {
        key: event.id,
        kind: isTurn ? 'turn' : 'event',
        event,
        lane,
        shape,
        tone,
        through: lanesExcept(lane),
        above: wasActive,
        below: true,
        forkFrom: null,
        mergeInto: null,
        turnNo: isTurn ? turnNo : null,
        rerun,
        gapMs: null,
      };
      active.add(lane);
    }

    rowIndex.set(event.id, rows.length);
    rows.push(row);
    previousVisible = event;
  }

  // 最后一行：会话已停下时子午线到此为止；仍在进行时延伸到"此刻"。
  const last = rows.at(-1);
  if (last && last.lane === 0) last.below = options.open ?? false;

  const current: SessionEvent | null = latestStatus;
  for (const action of actions.values()) {
    action.pending = action.response === null && current !== null && current.id === action.requestedBy.id;
  }

  return {
    rows,
    laneCount,
    threads,
    toolUses,
    modelStarts,
    usageByTurn,
    turnStarts,
    actions,
    rowIndex,
    lastEvent: events.at(-1) ?? null,
  };
}
