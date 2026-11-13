import { describe, expect, it } from 'vitest';

import { EventLog } from '@mocks/fixtures/event-log';
import {
  ACTION_EXPORT,
  buildQ3Scenario,
  Q3_FIXED_START,
  THREAD_ANALYST,
  THREAD_MAIN,
  THREAD_POLICY,
} from '@mocks/fixtures/q3-session';

import { buildTimeline } from './model';

const scenario = buildQ3Scenario(Q3_FIXED_START);
const history = [...scenario.turnOne, ...scenario.turnTwo];

describe('buildTimeline', () => {
  it('不单独成行的事件不出现在行里，轮次开始成为带序号的主刻度', () => {
    const model = buildTimeline(history);
    const types = model.rows.map((r) => r.event?.type ?? 'gap');
    expect(types).not.toContain('span.model_request_start');
    expect(types).not.toContain('session.usage');
    expect(types).not.toContain('session.message_consumed');
    expect(types).not.toContain('session.thread_status_running');
    const turns = model.rows.filter((r) => r.kind === 'turn');
    expect(turns.map((r) => r.turnNo)).toEqual([1, 2]);
  });

  it('两轮之间相隔 4 分钟，插入一个间隔行', () => {
    const model = buildTimeline(history);
    const gaps = model.rows.filter((r) => r.kind === 'gap');
    expect(gaps).toHaveLength(1);
    expect(gaps[0]!.gapMs).toBeGreaterThan(240_000);
    const gapIndex = model.rows.indexOf(gaps[0]!);
    expect(model.rows[gapIndex - 1]!.event?.type).toBe('session.status_idle');
    expect(model.rows[gapIndex + 1]!.event?.type).toBe('user.message');
  });

  it('子线程从主线分叉到各自的线，事件落在自己的线上，结束时并回主线并释放线号', () => {
    const model = buildTimeline(history);
    expect(model.laneCount).toBe(3);
    const forks = model.rows.filter((r) => r.event?.type === 'session.thread_created');
    expect(forks.map((r) => [r.lane, r.forkFrom, r.above, r.below])).toEqual([
      [1, 0, false, true],
      [2, 0, false, true],
    ]);
    const analystRows = model.rows.filter((r) => r.event?.thread_id === THREAD_ANALYST);
    expect(new Set(analystRows.map((r) => r.lane))).toEqual(new Set([1]));
    const merges = model.rows.filter((r) => r.mergeInto !== null);
    expect(merges.map((r) => [r.lane, r.mergeInto, r.below])).toEqual([
      [2, 0, false],
      [1, 0, false],
    ]);
    // 子线程运行期间，主线上的行有两条子线程线贯穿；全部结束后只剩主线。
    const policyResult = model.rows.find(
      (r) => r.event?.type === 'agent.tool_result' && r.event.data.tool_use_id === 'toolu_01JCQ8M5C8',
    );
    expect(policyResult?.through).toEqual([1]);
    const summary = model.rows.find(
      (r) => r.event?.type === 'agent.message' && r.event.thread_id === THREAD_MAIN,
    );
    expect(summary?.through).toEqual([]);
  });

  it('线程信息：主线程与两个子线程，结束后带停止原因', () => {
    const model = buildTimeline(history);
    expect(model.threads.map((t) => [t.threadId, t.agentName, t.lane, t.status, t.stopReason])).toEqual([
      [THREAD_MAIN, null, 0, 'running', null],
      [THREAD_ANALYST, 'data-analyst', 1, 'idle', 'end_turn'],
      [THREAD_POLICY, 'policy-reader', 2, 'idle', 'end_turn'],
    ]);
  });

  it('等待回应的动作在回应之前为待处理，回应之后不再是', () => {
    const waiting = buildTimeline(history, { open: true });
    expect(waiting.actions.get(ACTION_EXPORT)?.pending).toBe(true);
    expect(waiting.rows.at(-1)?.below).toBe(true);

    const continuation = scenario.afterResponse(true).flatMap((s) => ('event' in s ? [s.event] : []));
    const resolved = buildTimeline([...history, ...continuation]);
    const action = resolved.actions.get(ACTION_EXPORT);
    expect(action?.pending).toBe(false);
    const response = action?.response;
    expect(response?.type === 'user.tool_confirmation' && response.data.approved).toBe(true);
    expect(resolved.rows.filter((r) => r.kind === 'turn').map((r) => r.turnNo)).toEqual([1, 2, 3]);
    expect(resolved.rows.at(-1)?.below).toBe(false);
  });

  it('模型请求的起止事件按 request_id 配对，本轮用量按 turn_id 归集', () => {
    const model = buildTimeline(history);
    const end = model.rows.find((r) => r.event?.type === 'span.model_request_end');
    expect(
      end?.event?.type === 'span.model_request_end' && model.modelStarts.has(end.event.data.request_id),
    ).toBe(true);
    expect(model.usageByTurn.size).toBe(1);
  });

  it('同一轮被重新调度后再次运行，序号不变并标为重新执行', () => {
    const log = new EventLog('ses_01JCRESCHEDULE00000000000', Q3_FIXED_START);
    const turn = { thread: 'thr_main', turn: 'trn_1' };
    log.add(
      'user.message',
      { content: [{ type: 'text', text: '生成周报' }], delivery: 'queue' },
      { ...turn, turn: null },
    );
    log.add('session.status_running', {}, turn);
    log.add('session.status_rescheduled', { reason: 'lease_expired' }, { ...turn, after: 30_000 });
    log.add('session.status_running', {}, { ...turn, after: 5_000 });
    const model = buildTimeline(log.events);
    const turns = model.rows.filter((r) => r.kind === 'turn');
    expect(turns.map((r) => [r.turnNo, r.rerun])).toEqual([
      [1, false],
      [1, true],
    ]);
  });
});
