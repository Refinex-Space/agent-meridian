/**
 * 固定的 1 万条事件（性能验收用，frontend-design.md「性能预算」）：同一个种子总是生成完全相同的序列。
 * 内容是一个长期运行的工单处理会话：每轮读取工单、检索知识库、执行脚本，部分轮次并行拉起子智能体，轮次之间有空闲间隔。
 */
import type { SessionEvent } from '@/lib/api/types';

import { EventLog, text, usage } from './event-log';

export const LARGE_SESSION_ID = 'ses_01JCLARGE9T4V7X0Z3B6D9F2HK';
const MAIN = 'thr_01JCLARGEMAIN0000000000000';
const MODEL = 'mdl_01JBW2QP7K3M9X4V1R6T8Y2ZAB';

export const LARGE_MODEL_NAMES: Record<string, string> = { [MODEL]: 'qwen-plus' };

/** mulberry32：小而确定的伪随机数发生器。 */
function random(seed: number): () => number {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

const REQUESTS = [
  '工单 #{n}：门店收银系统在月结时报错，帮我查一下原因并给出处理步骤。',
  '工单 #{n}：客户反映会员积分没有到账，核对一下最近 7 天的积分流水。',
  '工单 #{n}：华南仓的出库单与订单数量不一致，找出差异并说明原因。',
  '工单 #{n}：供应商对账单金额比系统多了一笔，确认是不是重复开票。',
  '工单 #{n}：新上线的满减活动在小程序端没有生效，排查配置。',
];
const QUERIES = [
  '月结 报错 收银',
  '积分 到账 延迟 规则',
  '出库单 差异 盘点',
  '对账 重复开票 处理',
  '满减 活动 小程序 配置',
];
const COMMANDS = [
  'python scripts/check_ledger.py --store {n} --month 2026-10',
  'python scripts/points_audit.py --member {n} --days 7',
  'python scripts/diff_outbound.py --warehouse 华南 --order {n}',
  'python scripts/reconcile.py --vendor {n} --period 2026Q3',
  'python scripts/promo_check.py --channel miniapp --promo {n}',
];
const REPLIES = [
  '原因是月结任务在 23:58 与库存快照并发执行，锁等待超时。已给出两个处理步骤：先重跑当天的月结，再把快照任务挪到 00:10。',
  '积分没有到账是因为订单在积分结算前发生了部分退款，系统按规则冻结了 48 小时。冻结到期后会自动入账，不需要人工处理。',
  '差异来自 3 张拆单：订单在拣货时被拆成两个包裹，出库单按包裹统计。实际出库数量与订单一致，没有缺货。',
  '确认是重复开票：同一张发货单在 10 月 12 日和 10 月 14 日各开了一次。建议供应商作废第二张发票并重新出具对账单。',
  '小程序端的活动渠道配置漏选了"小程序"，活动只对 App 生效。补充渠道后约 5 分钟生效。',
];

function pick<T>(items: readonly T[], r: () => number): T {
  return items[Math.floor(r() * items.length)]!;
}

/** 生成恰好 `count` 条事件（默认 1 万）。 */
export function buildLargeSession(count = 10_000, seed = 20261113): SessionEvent[] {
  const r = random(seed);
  const log = new EventLog(LARGE_SESSION_ID, new Date('2026-11-10T01:00:00.000Z'));
  let turnNo = 0;
  let request = 0;
  let threadNo = 0;

  while (log.events.length < count) {
    turnNo += 1;
    const turn = `trn_${String(turnNo).padStart(26, '0')}`;
    const t = { thread: MAIN, turn };
    const n = 4000 + Math.floor(r() * 5000);
    const kind = Math.floor(r() * REQUESTS.length);
    log.add(
      'user.message',
      { content: text(REQUESTS[kind]!.replace('{n}', String(n))), delivery: 'queue' },
      {
        thread: MAIN,
        turn: null,
        after: r() < 0.3 ? 120_000 + Math.floor(r() * 600_000) : 8_000 + Math.floor(r() * 20_000),
      },
    );
    log.add('session.status_running', {}, { ...t, after: 100 });
    const steps = 1 + Math.floor(r() * 4);
    for (let s = 0; s < steps; s += 1) {
      request += 1;
      const req = `req_${request}`;
      log.add(
        'span.model_request_start',
        { request_id: req, model_id: MODEL, context: null },
        { ...t, after: 20 },
      );
      if (r() < 0.3) {
        log.add(
          'agent.thinking',
          { block_id: `blk_t${request}`, text: '先确认问题出现的时间范围，再对照相关流水与配置。' },
          { ...t, after: 600 },
        );
      }
      log.add(
        'span.model_request_end',
        {
          request_id: req,
          model_id: MODEL,
          usage: usage(2000 + Math.floor(r() * 9000), 80 + Math.floor(r() * 400)),
        },
        { ...t, after: 800 + Math.floor(r() * 2000) },
      );
      const useId = `toolu_${request}`;
      if (r() < 0.5) {
        log.add(
          'agent.tool_use',
          { tool_use_id: useId, name: 'knowledge_search', input: { query: pick(QUERIES, r), top_k: 5 } },
          { ...t, after: 5 },
        );
        log.add(
          'agent.tool_result',
          {
            tool_use_id: useId,
            name: 'knowledge_search',
            state: 'success',
            permission: 'allowed',
            content: text(
              '命中 5 条：《门店系统常见问题处理手册》第 4 章、《结算规则说明（2026 版）》第 2 节……',
            ),
            content_ref: null,
          },
          { ...t, after: 300 + Math.floor(r() * 600) },
        );
      } else {
        const failed = r() < 0.08;
        log.add(
          'agent.tool_use',
          { tool_use_id: useId, name: 'bash', input: { command: COMMANDS[kind]!.replace('{n}', String(n)) } },
          { ...t, after: 5 },
        );
        log.add(
          'agent.tool_result',
          {
            tool_use_id: useId,
            name: 'bash',
            state: failed ? 'error' : 'success',
            permission: 'allowed',
            content: text(
              failed
                ? 'Traceback (most recent call last):\n  TimeoutError: 连接数据库超时'
                : '检查完成：发现 3 条异常记录，已写入 output/report.csv',
            ),
            content_ref: null,
          },
          { ...t, after: 900 + Math.floor(r() * 3000) },
        );
      }
    }
    if (r() < 0.25) {
      // 并行拉起 1 至 3 个子智能体。
      const children = 1 + Math.floor(r() * 3);
      const ids: string[] = [];
      for (let c = 0; c < children; c += 1) {
        threadNo += 1;
        const threadId = `thr_${String(threadNo).padStart(26, '0')}`;
        ids.push(threadId);
        log.add(
          'agent.tool_use',
          {
            tool_use_id: `toolu_s${threadNo}`,
            name: 'spawn_subagent',
            input: { subagent: 'ticket-checker', task: '核对工单涉及的流水' },
          },
          { ...t, after: 3 },
        );
        log.add(
          'session.thread_created',
          {
            thread_id: threadId,
            parent_thread_id: MAIN,
            parent_tool_use_id: `toolu_s${threadNo}`,
            agent_name: 'ticket-checker',
          },
          { ...t, after: 10 },
        );
      }
      for (const threadId of ids) {
        const c = { thread: threadId, turn };
        log.add(
          'agent.message',
          { block_id: `blk_${threadId}`, content: text('核对完成，流水与系统记录一致。') },
          { ...c, after: 900 },
        );
      }
      for (const threadId of ids.reverse()) {
        log.add(
          'session.thread_status_idle',
          { thread_id: threadId, stop_reason: 'end_turn' },
          { thread: threadId, turn, after: 20 },
        );
      }
    }
    log.add(
      'agent.message',
      { block_id: `blk_m${turnNo}`, content: text(REPLIES[kind]!) },
      { ...t, after: 1500 },
    );
    log.add('session.status_idle', { stop_reason: 'end_turn' }, { ...t, after: 10 });
  }
  return log.events.slice(0, count);
}
