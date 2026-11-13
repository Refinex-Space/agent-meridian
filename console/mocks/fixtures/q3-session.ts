/**
 * 原型数据：华东大区运营在"经营分析助手"里分析第三季度销售。
 *
 * - 第 1 轮：检索季度经营月报，同时拉起两个子智能体（数据分析、政策解读），汇总出三个下滑品类。
 * - 4 分钟后第 2 轮：要求导出门店明细，`bash` 写文件需要批准，会话停在等待处理。
 * - 批准（或拒绝）之后的恢复轮：执行导出并回复，回复以增量推送。
 */
import type { Schemas, Session, SessionEvent, StreamOnlyEvent } from '@/lib/api/types';

import { EventLog, text, usage } from './event-log';

export const Q3_SESSION_ID = 'ses_01JCQ8M3T7V5X2K9P4R6W1Y8ZD';
export const Q3_AGENT_ID = 'agt_01JBX4E2R8M6T1K9V3P7W5Y0QA';
export const THREAD_MAIN = 'thr_01JCQ8M3TA2N7R4V9X1K6P3W5Z';
export const THREAD_ANALYST = 'thr_01JCQ8N0B4D7F2H6K9M3P5R8TV';
export const THREAD_POLICY = 'thr_01JCQ8N0B9E3G5J8L1N4Q7S2UX';
export const ACTION_EXPORT = 'act_01JCQ9C6W2Y5A8D1F4H7K0M3PS';

const MODEL_PLUS = 'mdl_01JBW2QP7K3M9X4V1R6T8Y2ZAB';
const MODEL_FLASH = 'mdl_01JBW2QF5H8N2Q6S9V3X7Z1BCD';

/** 模型 ID 到显示名（会话详情页从模型目录取得，原型里写死）。 */
export const MODEL_NAMES: Record<string, string> = {
  [MODEL_PLUS]: 'qwen-plus',
  [MODEL_FLASH]: 'qwen3.7-flash',
};

export const Q3_AGENT: Schemas['Agent'] = {
  id: Q3_AGENT_ID,
  name: '经营分析助手',
  description: '读取经营月报与销售数据，回答区域经营问题',
  current_version: {
    id: 'agv_01JCK2V8P4R7T1W5Y9B3D6F0HJ',
    version: 12,
    published_at: '2026-11-10T08:40:00.000Z',
  },
  created_at: '2026-09-02T03:12:00.000Z',
  updated_at: '2026-11-10T08:40:00.000Z',
};

export function q3Session(status: Session['status'], lastSeq: number, createdAt: string): Session {
  return {
    id: Q3_SESSION_ID,
    agent_id: Q3_AGENT_ID,
    agent_version_id: Q3_AGENT.current_version.id,
    environment_id: 'env_01JBZ7Q3M8T2V6X0B4D9F5H1KN',
    environment_revision: 3,
    end_user_id: 'u_8231',
    end_user_attributes: { acl_tags: ['region:east', 'dept:ops'] },
    interaction: 'attended',
    status,
    metadata: { channel: 'ops-portal' },
    custom_tools: [],
    last_seq: lastSeq,
    created_at: createdAt,
    updated_at: createdAt,
    terminated_at: null,
    deleted_at: null,
  };
}

/** 一条要推送的消息：持久化事件或只在流上的增量，`delay` 是距上一条的毫秒数。 */
export type StreamStep =
  { delay: number; event: SessionEvent } | { delay: number; streamOnly: StreamOnlyEvent };

function context(system: number, skills: number, history: number, tools: number, injected: number) {
  return {
    system_prompt: { count: 1, tokens: system },
    skills: { count: skills > 0 ? 3 : 0, tokens: skills },
    history: { count: Math.max(1, Math.round(history / 400)), tokens: history },
    tool_results: { count: Math.round(tools / 600), tokens: tools },
    injected: { count: injected > 0 ? 1 : 0, tokens: injected },
  };
}

const MAIN = { thread: THREAD_MAIN };

/** 第 1 轮：检索、两个并行的子智能体、汇总。 */
function turnOne(log: EventLog): void {
  const turn = 'trn_01JCQ8M3V1X4Z7B0D3F6H9K2MP';
  const t = { ...MAIN, turn };
  log.add(
    'user.message',
    {
      content: text('帮我看一下第三季度华东区的销售情况，和去年同期比，重点说说下滑的品类。'),
      delivery: 'queue',
    },
    { ...MAIN, turn: null },
  );
  log.add('session.status_running', {}, { ...t, after: 120 });
  log.add('session.message_consumed', { event_ids: [log.events[0]!.id] }, { ...t, after: 4 });
  log.add(
    'span.model_request_start',
    { request_id: 'req_01JCQ8M3W2', model_id: MODEL_PLUS, context: context(1840, 620, 96, 0, 410) },
    { ...t, after: 30 },
  );
  log.add(
    'agent.thinking',
    {
      block_id: 'blk_01JCQ8M3X1',
      text: '需要两部分数据：知识库里的季度经营月报给出口径和结论，销售明细给出各品类的同比。先查月报确认口径，再把计算和政策核对交给两个子智能体并行做。',
    },
    { ...t, after: 1180 },
  );
  log.add(
    'span.model_request_end',
    { request_id: 'req_01JCQ8M3W2', model_id: MODEL_PLUS, usage: usage(3240, 186, 2048) },
    { ...t, after: 260 },
  );
  log.add(
    'agent.tool_use',
    {
      tool_use_id: 'toolu_01JCQ8M4A1',
      name: 'knowledge_search',
      input: { query: '2026 年第三季度 华东区 经营月报 品类 同比', top_k: 8 },
    },
    { ...t, after: 6 },
  );
  log.add(
    'agent.tool_result',
    {
      tool_use_id: 'toolu_01JCQ8M4A1',
      name: 'knowledge_search',
      state: 'success',
      permission: 'allowed',
      content: text(
        '命中 8 条。最相关的是《2026Q3 华东区经营月报（9 月）》第 3 节「品类表现」：华东区第三季度销售额 4.86 亿元，同比增长 3.1%；小家电、厨房用品同比下滑，个人护理小幅下滑。口径：含税销售额，不含团购与员工内购。',
      ),
      content_ref: null,
    },
    { ...t, after: 640 },
  );
  log.add(
    'span.model_request_start',
    { request_id: 'req_01JCQ8M4B7', model_id: MODEL_PLUS, context: context(1840, 620, 3420, 1180, 410) },
    { ...t, after: 20 },
  );
  log.add(
    'span.model_request_end',
    { request_id: 'req_01JCQ8M4B7', model_id: MODEL_PLUS, usage: usage(5120, 312, 3072) },
    { ...t, after: 1920 },
  );
  log.add(
    'agent.tool_use',
    {
      tool_use_id: 'toolu_01JCQ8M5C2',
      name: 'spawn_subagent',
      input: {
        subagent: 'data-analyst',
        task: '读取 data/sales_q3_2026.csv 与 data/sales_q3_2025.csv，按品类计算华东区第三季度销售额同比，列出下滑超过 5% 的品类，以及每个品类下滑最多的两家门店。',
      },
    },
    { ...t, after: 4 },
  );
  log.add(
    'agent.tool_use',
    {
      tool_use_id: 'toolu_01JCQ8M5C8',
      name: 'spawn_subagent',
      input: {
        subagent: 'policy-reader',
        task: '在知识库中查找 2026 年第三季度华东区的促销与价格政策调整，说明其中与小家电、厨房用品、个人护理相关的变化。',
      },
    },
    { ...t, after: 2 },
  );
  log.add(
    'session.thread_created',
    {
      thread_id: THREAD_ANALYST,
      parent_thread_id: THREAD_MAIN,
      parent_tool_use_id: 'toolu_01JCQ8M5C2',
      agent_name: 'data-analyst',
    },
    { ...t, after: 18 },
  );
  log.add(
    'session.thread_created',
    {
      thread_id: THREAD_POLICY,
      parent_thread_id: THREAD_MAIN,
      parent_tool_use_id: 'toolu_01JCQ8M5C8',
      agent_name: 'policy-reader',
    },
    { ...t, after: 3 },
  );
  const a = { thread: THREAD_ANALYST, turn };
  const p = { thread: THREAD_POLICY, turn };
  log.add('session.thread_status_running', { thread_id: THREAD_ANALYST }, { ...a, after: 2 });
  log.add('session.thread_status_running', { thread_id: THREAD_POLICY }, { ...p, after: 1 });
  log.add(
    'span.model_request_start',
    { request_id: 'req_01JCQ8M5D1', model_id: MODEL_FLASH, context: context(960, 0, 180, 0, 120) },
    { ...a, after: 40 },
  );
  log.add(
    'span.model_request_start',
    { request_id: 'req_01JCQ8M5D4', model_id: MODEL_FLASH, context: context(910, 0, 160, 0, 120) },
    { ...p, after: 6 },
  );
  log.add(
    'span.model_request_end',
    { request_id: 'req_01JCQ8M5D1', model_id: MODEL_FLASH, usage: usage(1260, 84) },
    { ...a, after: 820 },
  );
  log.add(
    'agent.tool_use',
    {
      tool_use_id: 'toolu_01JCQ8M5E3',
      name: 'read_file',
      input: { path: 'data/sales_q3_2026.csv', limit: 200 },
    },
    { ...a, after: 3 },
  );
  log.add(
    'span.model_request_end',
    { request_id: 'req_01JCQ8M5D4', model_id: MODEL_FLASH, usage: usage(1180, 62) },
    { ...p, after: 90 },
  );
  log.add(
    'agent.tool_use',
    {
      tool_use_id: 'toolu_01JCQ8M5E9',
      name: 'knowledge_search',
      input: { query: '第三季度 促销 价格调整 小家电 厨房用品 个人护理 华东', top_k: 5 },
    },
    { ...p, after: 2 },
  );
  log.add(
    'agent.tool_result',
    {
      tool_use_id: 'toolu_01JCQ8M5E3',
      name: 'read_file',
      state: 'success',
      permission: 'allowed',
      content: text(
        '门店,品类,日期,销售额,件数\n上海徐汇,小家电,2026-07-01,18420.00,46\n上海徐汇,厨房用品,2026-07-01,9310.50,27\n…（共 18,432 行）',
      ),
      content_ref: null,
    },
    { ...a, after: 210 },
  );
  log.add(
    'agent.tool_use',
    {
      tool_use_id: 'toolu_01JCQ8M5F6',
      name: 'bash',
      input: {
        command: 'python scripts/yoy.py --region 华东 --by category --threshold -0.05 --top-stores 2',
        description: '按品类计算同比并找出下滑最多的门店',
      },
    },
    { ...a, after: 1460 },
  );
  log.add(
    'agent.tool_result',
    {
      tool_use_id: 'toolu_01JCQ8M5E9',
      name: 'knowledge_search',
      state: 'success',
      permission: 'allowed',
      content: text(
        '命中 5 条。《华东区 2026 年 7 月价格与促销调整通知》：小家电取消满 299 减 40 的常态满减；《8 月厨房用品调价说明》：主力单品提价 6% 至 9%。个人护理第三季度没有政策调整。',
      ),
      content_ref: null,
    },
    { ...p, after: 120 },
  );
  log.add(
    'span.model_request_start',
    { request_id: 'req_01JCQ8M5G2', model_id: MODEL_FLASH, context: context(910, 0, 920, 860, 120) },
    { ...p, after: 15 },
  );
  log.add(
    'span.model_request_end',
    { request_id: 'req_01JCQ8M5G2', model_id: MODEL_FLASH, usage: usage(2140, 168) },
    { ...p, after: 1340 },
  );
  log.add(
    'agent.message',
    {
      block_id: 'blk_01JCQ8M5G5',
      content: text(
        '第三季度华东区有两项相关调整：7 月起小家电取消满 299 减 40 的常态满减；8 月厨房用品主力单品提价 6% 至 9%。个人护理本季度没有促销或价格变化。',
      ),
    },
    { ...p, after: 6 },
  );
  log.add(
    'session.thread_status_idle',
    { thread_id: THREAD_POLICY, stop_reason: 'end_turn' },
    { ...p, after: 8 },
  );
  log.add(
    'agent.tool_result',
    {
      tool_use_id: 'toolu_01JCQ8M5C8',
      name: 'spawn_subagent',
      state: 'success',
      permission: 'allowed',
      content: text(
        '第三季度华东区有两项相关调整：7 月起小家电取消满 299 减 40 的常态满减；8 月厨房用品主力单品提价 6% 至 9%。个人护理本季度没有促销或价格变化。',
      ),
      content_ref: null,
    },
    { ...t, after: 4 },
  );
  log.add(
    'agent.tool_result',
    {
      tool_use_id: 'toolu_01JCQ8M5F6',
      name: 'bash',
      state: 'success',
      permission: 'allowed',
      content: text(
        '品类        2025Q3(万元)  2026Q3(万元)  同比\n小家电          6,412.3      5,617.2   -12.4%\n厨房用品        4,980.6      4,587.1    -7.9%\n个人护理        3,205.4      3,025.9    -5.6%\n下滑门店：小家电 上海徐汇 -21.8%、杭州滨江 -18.5%；厨房用品 南京新街口 -14.2%、苏州园区 -11.0%',
      ),
      content_ref: null,
    },
    { ...a, after: 1880 },
  );
  log.add(
    'span.model_request_start',
    { request_id: 'req_01JCQ8M5H3', model_id: MODEL_FLASH, context: context(960, 0, 1460, 1620, 120) },
    { ...a, after: 12 },
  );
  log.add(
    'span.model_request_end',
    { request_id: 'req_01JCQ8M5H3', model_id: MODEL_FLASH, usage: usage(3020, 214) },
    { ...a, after: 1510 },
  );
  log.add(
    'agent.message',
    {
      block_id: 'blk_01JCQ8M5H6',
      content: text(
        '同比下滑超过 5% 的品类：小家电 -12.4%（上海徐汇、杭州滨江两店合计贡献下滑的 61%），厨房用品 -7.9%（南京新街口、苏州园区），个人护理 -5.6%。其余品类持平或增长。',
      ),
    },
    { ...a, after: 5 },
  );
  log.add(
    'session.thread_status_idle',
    { thread_id: THREAD_ANALYST, stop_reason: 'end_turn' },
    { ...a, after: 7 },
  );
  log.add(
    'agent.tool_result',
    {
      tool_use_id: 'toolu_01JCQ8M5C2',
      name: 'spawn_subagent',
      state: 'success',
      permission: 'allowed',
      content: text(
        '同比下滑超过 5% 的品类：小家电 -12.4%（上海徐汇、杭州滨江两店合计贡献下滑的 61%），厨房用品 -7.9%（南京新街口、苏州园区），个人护理 -5.6%。其余品类持平或增长。',
      ),
      content_ref: null,
    },
    { ...t, after: 4 },
  );
  log.add(
    'span.model_request_start',
    { request_id: 'req_01JCQ8M6J1', model_id: MODEL_PLUS, context: context(1840, 620, 4310, 2860, 410) },
    { ...t, after: 18 },
  );
  log.add(
    'span.model_request_end',
    { request_id: 'req_01JCQ8M6J1', model_id: MODEL_PLUS, usage: usage(9850, 640, 5120) },
    { ...t, after: 2780 },
  );
  log.add(
    'agent.message',
    {
      block_id: 'blk_01JCQ8M6J4',
      content: text(
        '第三季度华东区销售额 4.86 亿元，同比增长 3.1%，但有三个品类下滑超过 5%：\n\n1. 小家电 -12.4%。7 月取消了满 299 减 40 的常态满减，成交笔数下降 15%；下滑集中在上海徐汇和杭州滨江两家门店。\n2. 厨房用品 -7.9%。8 月主力单品提价 6% 至 9%，销量下降抵消了涨价，南京新街口、苏州园区下滑最多。\n3. 个人护理 -5.6%。本季度没有政策变化，可能与季节有关，建议单独核对。\n\n需要的话，我可以把这三个品类的门店明细导出成表格。',
      ),
    },
    { ...t, after: 6 },
  );
  log.add(
    'session.usage',
    {
      model_id: MODEL_PLUS,
      usage: usage(25810, 1666, 10240),
      session_usage: usage(25810, 1666, 10240),
      models: [
        { model_id: MODEL_FLASH, calls: 5, usage: usage(8600, 528) },
        { model_id: MODEL_PLUS, calls: 3, usage: usage(17210, 1138, 10240) },
      ],
    },
    { ...t, after: 9 },
  );
  log.add('session.status_idle', { stop_reason: 'end_turn' }, { ...t, after: 2 });
}

const EXPORT_COMMAND =
  'python scripts/export_decline.py --categories 小家电,厨房用品,个人护理 --out reports/q3_decline_by_store.xlsx';

/** 第 2 轮：要求导出，`bash` 写文件需要批准。 */
function turnTwo(log: EventLog): void {
  const turn = 'trn_01JCQ9C4R8T1V4X7Z0B3D6F9HK';
  const t = { ...MAIN, turn };
  const message = log.add(
    'user.message',
    { content: text('把下滑品类的门店明细导出成表格，放到工作区里。'), delivery: 'queue' },
    { ...MAIN, turn: null, after: 252_000 },
  );
  log.add('session.status_running', {}, { ...t, after: 110 });
  log.add('session.message_consumed', { event_ids: [message.id] }, { ...t, after: 3 });
  log.add(
    'span.model_request_start',
    { request_id: 'req_01JCQ9C5A2', model_id: MODEL_PLUS, context: context(1840, 620, 5240, 2860, 410) },
    { ...t, after: 24 },
  );
  log.add(
    'span.model_request_end',
    { request_id: 'req_01JCQ9C5A2', model_id: MODEL_PLUS, usage: usage(11240, 96, 9216) },
    { ...t, after: 1310 },
  );
  log.add(
    'agent.tool_use',
    {
      tool_use_id: 'toolu_01JCQ9C5B4',
      name: 'bash',
      input: { command: EXPORT_COMMAND, description: '导出下滑品类的门店明细' },
    },
    { ...t, after: 5 },
  );
  log.add(
    'session.status_requires_action',
    {
      actions: [
        {
          id: ACTION_EXPORT,
          type: 'tool_confirmation',
          tool_use_id: 'toolu_01JCQ9C5B4',
          tool_name: 'bash',
          input: { command: EXPORT_COMMAND, description: '导出下滑品类的门店明细' },
          expires_at: new Date(log.now + 30 * 60_000).toISOString(),
          suggested_rules: ['python:*'],
        },
      ],
    },
    { ...t, after: 40 },
  );
}

/** 回应之后的恢复轮。批准时执行导出；拒绝时按拒绝理由直接在对话里给出结果。 */
function recoveryTurn(log: EventLog, approved: boolean): StreamStep[] {
  const turn = 'trn_01JCQA2M7P0R3T6V9X2Z5B8D1F';
  const t = { ...MAIN, turn };
  const steps: StreamStep[] = [];
  const push = (delay: number, event: SessionEvent) => steps.push({ delay, event });
  const delta = (delay: number, blockId: string, chunk: string) =>
    steps.push({
      delay,
      streamOnly: {
        id: null,
        seq: null,
        session_id: log.sessionId,
        thread_id: THREAD_MAIN,
        turn_id: turn,
        type: 'event_delta',
        created_at: new Date(log.now).toISOString(),
        data: { block_id: blockId, kind: 'text', delta: chunk },
      },
    });

  push(
    0,
    log.add(
      'user.tool_confirmation',
      approved
        ? {
            action_id: ACTION_EXPORT,
            approved: true,
            updated_input: null,
            deny_reason: null,
            remember_scope: 'session',
            remember_rule: 'python:*',
          }
        : {
            action_id: ACTION_EXPORT,
            approved: false,
            updated_input: null,
            deny_reason: '先不要写文件，直接在对话里列出每个品类下滑最多的三家门店。',
            remember_scope: 'none',
            remember_rule: null,
          },
      { ...MAIN, turn: null },
    ),
  );
  push(420, log.add('session.status_running', {}, { ...t, after: 420 }));
  const result = approved
    ? log.add(
        'agent.tool_result',
        {
          tool_use_id: 'toolu_01JCQ9C5B4',
          name: 'bash',
          state: 'success',
          permission: 'approved',
          content: text('已写入 reports/q3_decline_by_store.xlsx：3 个工作表，126 行。'),
          content_ref: null,
        },
        { ...t, after: 2380 },
      )
    : log.add(
        'agent.tool_result',
        {
          tool_use_id: 'toolu_01JCQ9C5B4',
          name: 'bash',
          state: 'denied',
          permission: 'denied',
          content: text('调用被拒绝：先不要写文件，直接在对话里列出每个品类下滑最多的三家门店。'),
          content_ref: null,
        },
        { ...t, after: 60 },
      );
  push(approved ? 2380 : 60, result);
  push(
    40,
    log.add(
      'span.model_request_start',
      { request_id: 'req_01JCQA2N1C', model_id: MODEL_PLUS, context: context(1840, 620, 5380, 3120, 410) },
      { ...t, after: 40 },
    ),
  );
  const blockId = 'blk_01JCQA2N2E';
  const reply = approved
    ? '已导出到工作区 reports/q3_decline_by_store.xlsx，按品类分为 3 个工作表，共 126 家门店。每张表按下滑金额从高到低排序，第一列是门店，后面依次是 2025 年与 2026 年第三季度销售额和同比。'
    : '好的，不写文件。三个品类下滑最多的门店：小家电是上海徐汇 -21.8%、杭州滨江 -18.5%、宁波天一 -16.0%；厨房用品是南京新街口 -14.2%、苏州园区 -11.0%、合肥政务区 -9.7%；个人护理是无锡崇安 -9.1%、上海五角场 -8.4%、杭州湖滨 -7.6%。';
  steps.push({
    delay: 900,
    streamOnly: {
      id: null,
      seq: null,
      session_id: log.sessionId,
      thread_id: THREAD_MAIN,
      turn_id: turn,
      type: 'event_start',
      created_at: new Date(log.now).toISOString(),
      data: { block_id: blockId, kind: 'text', name: null },
    },
  });
  for (let i = 0; i < reply.length; i += 6) delta(90, blockId, reply.slice(i, i + 6));
  push(
    120,
    log.add(
      'span.model_request_end',
      { request_id: 'req_01JCQA2N1C', model_id: MODEL_PLUS, usage: usage(12480, 212, 10240) },
      { ...t, after: 3400 },
    ),
  );
  push(10, log.add('agent.message', { block_id: blockId, content: text(reply) }, { ...t, after: 10 }));
  push(
    30,
    log.add(
      'session.usage',
      {
        model_id: MODEL_PLUS,
        usage: usage(12480, 212, 10240),
        session_usage: usage(49530, 1974, 29696),
        models: [{ model_id: MODEL_PLUS, calls: 1, usage: usage(12480, 212, 10240) }],
      },
      { ...t, after: 30 },
    ),
  );
  push(10, log.add('session.status_idle', { stop_reason: 'end_turn' }, { ...t, after: 10 }));
  return steps;
}

export interface Q3Scenario {
  /** 第 1 轮全部事件。 */
  turnOne: SessionEvent[];
  /** 第 2 轮到等待批准为止的事件。 */
  turnTwo: SessionEvent[];
  /** 回应之后推送的消息。 */
  afterResponse: (approved: boolean) => StreamStep[];
  startedAt: string;
}

/** 以 `start` 为第一条事件的时间构造整段会话；同一个 start 得到完全相同的事件。 */
export function buildQ3Scenario(start: Date): Q3Scenario {
  const log = new EventLog(Q3_SESSION_ID, start);
  turnOne(log);
  const turnOneEvents = [...log.events];
  turnTwo(log);
  const turnTwoEvents = log.events.slice(turnOneEvents.length);
  const pausedAt = log.now;
  const lastSeq = log.events.at(-1)!.seq;
  return {
    turnOne: turnOneEvents,
    turnTwo: turnTwoEvents,
    afterResponse: (approved) => {
      // 每次回应都从等待批准的那一刻、同一个 seq 继续，故事反复播放结果一致。
      const continuation = new EventLog(Q3_SESSION_ID, new Date(pausedAt + 18_000), lastSeq + 1);
      return recoveryTurn(continuation, approved);
    },
    startedAt: start.toISOString(),
  };
}

/** 截图与静态故事用的固定起点：2026-11-13 10:02:11（UTC+8）。 */
export const Q3_FIXED_START = new Date('2026-11-13T02:02:11.204Z');
