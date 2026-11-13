/**
 * 原型项目的待处理动作（公开 API 的 PendingAction，`GET /v1/pending-actions`）：6 个等待回应，14 个已回应或超时。
 * 时间相对"现在"排布；建议规则的写法与各工具 `generateSuggestions` 给出的一致（bash 为命令前缀加 `:*`，文件为路径 glob，
 * 联网为 `domain:` 前缀）。第一条是会话详情原型中那次导出的审批。
 */
import type { PendingAction, Schemas } from '@/lib/api/types';

import { AGENT_CONTRACT, AGENT_POLICY, AGENT_SERVICE_QA, AGENT_STORE_PATROL, AGENT_WEEKLY } from './agents';
import { ACTION_EXPORT, Q3_AGENT_ID, Q3_SESSION_ID, THREAD_MAIN } from './q3-session';

/** 控制台用户的标识（OIDC 的 sub）到显示名：正式页面从成员接口取。 */
export const DECIDERS: Readonly<Record<string, string>> = {
  'f3a9c2e1-7b4d-4e58-9a61-2c8d0e5b7f14': '林晓雯',
  '0b7e4d92-5c3a-4f16-8e27-9d1a6c4b3e05': '周启航',
  key_01JB9W4M8Q2T6X0B4D8F2H6K0N: '经营驾驶舱 Key',
};

const [LIN, ZHOU, COCKPIT] = Object.keys(DECIDERS) as [string, string, string];

type Conclusion = Schemas['PendingActionConclusion'];

function conclusion(partial: Partial<Conclusion>): Conclusion {
  return {
    origin: 'caller',
    approved: null,
    deny_reason: null,
    remember_scope: null,
    remember_rule: null,
    decision: null,
    error: null,
    ...partial,
  };
}

interface Spec {
  id: string;
  session: string;
  agent: string;
  type: Schemas['PendingActionType'];
  tool: string;
  input: Record<string, unknown>;
  rules?: string[];
  /** 创建于多少毫秒之前。 */
  ago: number;
  /** 截止时间距创建的毫秒数，默认 30 分钟。 */
  ttl?: number;
  /** 回应或超时；`conclusion` 为 null 表示随会话终止作废。 */
  resolved?: {
    after: number;
    by: string | null;
    status?: 'resolved' | 'expired';
    conclusion: Conclusion | null;
  };
}

const MIN = 60_000;
const HOUR = 60 * MIN;

const SPECS: Spec[] = [
  {
    id: ACTION_EXPORT,
    session: Q3_SESSION_ID,
    agent: Q3_AGENT_ID,
    type: 'tool_confirmation',
    tool: 'bash',
    input: {
      command:
        'python scripts/export_decline.py --categories 小家电,厨房用品,个人护理 --out reports/q3_decline_by_store.xlsx',
      description: '导出下滑品类的门店明细',
    },
    rules: ['python scripts/export_decline.py:*', 'python:*'],
    ago: 40_000,
  },
  {
    id: 'act_01JCQA4N9R2T5W8Y1B4D7F0H3K',
    session: 'ses_01JCQA1K6M9P2R5T8W1Y4B7D0F',
    agent: AGENT_SERVICE_QA.id,
    type: 'tool_confirmation',
    tool: 'mcp__crm__update_ticket',
    input: {
      ticket_id: 'TK-20261113-0412',
      status: '待主管复核',
      note: '通话 08:41 处坐席承诺"今天之内全额退款"，超出一线权限，需主管复核后回电。',
    },
    ago: 3 * MIN,
  },
  {
    id: 'act_01JCQ9X2B5D8F1H4K7M0P3R6TW',
    session: 'ses_01JCQ9V8Z1B4D7F0H3K6M9P2RT',
    agent: AGENT_CONTRACT.id,
    type: 'tool_confirmation',
    tool: 'web_fetch',
    input: {
      url: 'https://intranet.east-retail.example.com/legal/templates/procurement-2026-v3.docx',
      prompt: '取出付款条件、违约责任、保密期限三节的原文',
    },
    rules: ['domain:intranet.east-retail.example.com'],
    ago: 6 * MIN,
  },
  {
    id: 'act_01JCQ9R7T0W3Y6A9C2E5G8J1LN',
    session: 'ses_01JCQ9N4Q7S0V3X6Z9B2D5F8HJ',
    agent: AGENT_STORE_PATROL.id,
    type: 'tool_confirmation',
    tool: 'write_file',
    input: {
      path: 'reports/patrol/2026-11-13/store-0213-整改清单.md',
      content:
        '# 0213 店（杭州西溪）巡检整改清单\n\n1. 冷柜 3 号温度 8.2℃，超出 0–4℃ 要求，今日内报修。\n2. 收银台 2 号缺少价签 11 处。\n3. 消防通道堆放纸箱，立即清理。\n',
    },
    rules: ['reports/patrol/**'],
    ago: 11 * MIN,
  },
  {
    id: 'act_01JCQ8Z5C8E1G4J7L0N3Q6S9VX',
    session: 'ses_01JCQ8W2Y5A8C1E4G7J0L3N6QS',
    agent: Q3_AGENT_ID,
    type: 'recovery_confirmation',
    tool: 'bash',
    input: { command: 'python scripts/refresh_sales_cache.py --region east --since 2026-10-01' },
    ago: 18 * MIN,
  },
  {
    id: 'act_01JCQ8T9V2X5Z8B1D4F7H0K3MP',
    session: 'ses_01JCQ8R6T9W2Y5A8C1E4G7J0LN',
    agent: AGENT_POLICY.id,
    type: 'custom_tool_result',
    tool: 'lookup_employee_grade',
    input: { employee_id: 'E10293' },
    ago: 24 * MIN,
    ttl: 60 * MIN,
  },
  // 历史：已回应、超时与作废。
  {
    id: 'act_01JCQ7M3P6R9T2W5Y8B1D4F7HK',
    session: 'ses_01JCQ7H2K8N4R0T6W2Y8B4D0FG',
    agent: AGENT_SERVICE_QA.id,
    type: 'tool_confirmation',
    tool: 'mcp__crm__update_ticket',
    input: { ticket_id: 'TK-20261113-0388', status: '已结案', note: '质检得分 92，无违规话术。' },
    ago: 52 * MIN,
    resolved: { after: 2 * MIN, by: LIN, conclusion: conclusion({ approved: true, remember_scope: 'none' }) },
  },
  {
    id: 'act_01JCQ6J8L1N4Q7S0V3X6Z9B2DF',
    session: 'ses_01JCQ6G5J8L1N4Q7S0V3X6Z9BD',
    agent: Q3_AGENT_ID,
    type: 'tool_confirmation',
    tool: 'bash',
    input: { command: 'python scripts/yoy_by_category.py --quarter 2026Q3 --region east' },
    rules: ['python scripts/yoy_by_category.py:*', 'python:*'],
    ago: 1.6 * HOUR,
    resolved: {
      after: 40_000,
      by: LIN,
      conclusion: conclusion({ approved: true, remember_scope: 'project', remember_rule: 'python:*' }),
    },
  },
  {
    id: 'act_01JCQ5F4H7K0M3P6R9T2W5Y8BC',
    session: 'ses_01JCQ5C1E4G7J0L3N6Q9S2V5XZ',
    agent: AGENT_CONTRACT.id,
    type: 'tool_confirmation',
    tool: 'web_fetch',
    input: { url: 'https://www.example-supplier.cn/about/qualifications' },
    rules: ['domain:www.example-supplier.cn'],
    ago: 2.4 * HOUR,
    resolved: {
      after: 5 * MIN,
      by: ZHOU,
      conclusion: conclusion({
        approved: false,
        deny_reason: '供应商资质以采购系统登记为准，不要抓取外网。',
      }),
    },
  },
  {
    id: 'act_01JCQ4B9D2F5H8K1M4P7R0T3WY',
    session: 'ses_01JCQ48Z1B4D7F0H3K6M9P2RTV',
    agent: AGENT_STORE_PATROL.id,
    type: 'tool_confirmation',
    tool: 'write_file',
    input: { path: 'reports/patrol/2026-11-13/store-0187-整改清单.md' },
    rules: ['reports/patrol/**'],
    ago: 3.1 * HOUR,
    resolved: {
      after: 30 * MIN,
      by: null,
      status: 'expired',
      conclusion: conclusion({ origin: 'timeout', approved: false }),
    },
  },
  {
    id: 'act_01JCQ3W5Y8A1C4E7G0J3L6N9QS',
    session: 'ses_01JCQ3T2V5X8Z1B4D7F0H3K6MP',
    agent: AGENT_WEEKLY.id,
    type: 'tool_confirmation',
    tool: 'bash',
    input: { command: 'python scripts/weekly_report.py --week 2026-W46 --send' },
    ago: 4.2 * HOUR,
    resolved: { after: 0, by: null, conclusion: conclusion({ origin: 'unattended', approved: false }) },
  },
  {
    id: 'act_01JCQ2R1T4W7Y0A3C6E9G2J5LN',
    session: 'ses_01JCQ2N8Q1S4V7X0Z3B6D9F2HK',
    agent: Q3_AGENT_ID,
    type: 'recovery_confirmation',
    tool: 'bash',
    input: { command: 'python scripts/refresh_sales_cache.py --region east --since 2026-10-01' },
    ago: 5 * HOUR,
    resolved: { after: 3 * MIN, by: LIN, conclusion: conclusion({ decision: 'retry' }) },
  },
  {
    id: 'act_01JCQ1M6P9R2T5W8Y1B4D7F0HK',
    session: 'ses_01JCQ1J3L6N9Q2S5V8X1Z4B7DF',
    agent: AGENT_POLICY.id,
    type: 'custom_tool_result',
    tool: 'lookup_employee_grade',
    input: { employee_id: 'E20871' },
    ago: 6.5 * HOUR,
    resolved: { after: 1_800, by: COCKPIT, conclusion: conclusion({ error: false }) },
  },
  {
    id: 'act_01JCQ0H2K5M8P1R4T7W0Y3A6CE',
    session: 'ses_01JCQ0E9G2J5L8N1Q4S7V0X3ZB',
    agent: AGENT_SERVICE_QA.id,
    type: 'tool_confirmation',
    tool: 'mcp__crm__update_ticket',
    input: { ticket_id: 'TK-20261112-1207', status: '已结案' },
    ago: 22 * HOUR,
    resolved: {
      after: 9 * MIN,
      by: ZHOU,
      conclusion: conclusion({ approved: true, remember_scope: 'session' }),
    },
  },
  {
    id: 'act_01JCPZC7E0G3J6L9N2Q5S8V1XA',
    session: 'ses_01JCPZ9X2Z5B8D1F4H7K0M3P6R',
    agent: AGENT_CONTRACT.id,
    type: 'plan_exit',
    tool: 'plan_exit',
    input: { plan: '1. 读取合同全文\n2. 对照模板逐条比对付款、违约、保密条款\n3. 输出差异表与修改建议' },
    ago: 26 * HOUR,
    resolved: { after: 4 * MIN, by: ZHOU, conclusion: conclusion({ approved: true }) },
  },
  {
    id: 'act_01JCPY7B0D3F6H9K2M5P8R1T4W',
    session: 'ses_01JCPY4Y7A0C3E6G9J2L5N8Q1S',
    agent: AGENT_STORE_PATROL.id,
    type: 'tool_confirmation',
    tool: 'bash',
    input: { command: 'rm -rf reports/patrol/2026-11-05' },
    rules: ['rm:*'],
    ago: 30 * HOUR,
    resolved: {
      after: 1 * MIN,
      by: LIN,
      conclusion: conclusion({ approved: false, deny_reason: '历史巡检记录要保留一年，不能删除。' }),
    },
  },
  {
    id: 'act_01JCPX2W5Y8A1C4E7G0J3L6N9Q',
    session: 'ses_01JCPWZ2B5D8F1H4K7M0P3R6TV',
    agent: Q3_AGENT_ID,
    type: 'tool_confirmation',
    tool: 'bash',
    input: { command: 'python scripts/store_traffic.py --month 2026-10 --region east' },
    rules: ['python scripts/store_traffic.py:*', 'python:*'],
    ago: 2 * 24 * HOUR,
    // 等待期间会话被归档，动作随之作废。
    resolved: { after: 12 * MIN, by: null, status: 'expired', conclusion: null },
  },
  {
    id: 'act_01JCPV8R1T4W7Y0A3C6E9G2J5L',
    session: 'ses_01JCPV5N8Q1S4V7X0Z3B6D9F2H',
    agent: AGENT_SERVICE_QA.id,
    type: 'tool_confirmation',
    tool: 'mcp__crm__update_ticket',
    input: { ticket_id: 'TK-20261111-0951', status: '待主管复核' },
    ago: 2.3 * 24 * HOUR,
    resolved: { after: 6 * MIN, by: LIN, conclusion: conclusion({ approved: true }) },
  },
  {
    id: 'act_01JCPT3M6P9R2T5W8Y1B4D7F0H',
    session: 'ses_01JCPT0J3L6N9Q2S5V8X1Z4B7D',
    agent: AGENT_POLICY.id,
    type: 'tool_confirmation',
    tool: 'web_search',
    input: { query: '2026 年 差旅住宿标准 一线城市 国企' },
    rules: [],
    ago: 3 * 24 * HOUR,
    resolved: {
      after: 2 * MIN,
      by: ZHOU,
      conclusion: conclusion({ approved: false, deny_reason: '只按内部制度回答。' }),
    },
  },
  {
    id: 'act_01JCPS8H1K4M7P0R3T6W9Y2B5D',
    session: 'ses_01JCPS5E8G1J4L7N0Q3S6V9X2Z',
    agent: AGENT_STORE_PATROL.id,
    type: 'tool_confirmation',
    tool: 'write_file',
    input: { path: 'reports/patrol/2026-11-10/store-0204-整改清单.md' },
    rules: ['reports/patrol/**'],
    ago: 3.2 * 24 * HOUR,
    resolved: {
      after: 3 * MIN,
      by: LIN,
      conclusion: conclusion({
        approved: true,
        remember_scope: 'project',
        remember_rule: 'reports/patrol/**',
      }),
    },
  },
];

function toAction(spec: Spec, now: number): PendingAction {
  const created = now - spec.ago;
  const resolved = spec.resolved;
  return {
    id: spec.id,
    session_id: spec.session,
    agent_id: spec.agent,
    thread_id: spec.session === Q3_SESSION_ID ? THREAD_MAIN : `thr_${spec.session.slice(4)}`,
    turn_id: `trn_${spec.id.slice(4)}`,
    type: spec.type,
    status: resolved ? (resolved.status ?? 'resolved') : 'pending',
    tool_use_id: `toolu_${spec.id.slice(4, 18)}`,
    tool_name: spec.tool,
    input: spec.input,
    suggested_rules: spec.rules ?? [],
    expires_at: new Date(created + (spec.ttl ?? 30 * MIN)).toISOString(),
    created_at: new Date(created).toISOString(),
    resolved_at: resolved ? new Date(created + resolved.after).toISOString() : null,
    decided_by: resolved?.by ?? null,
    conclusion: resolved?.conclusion ?? null,
  };
}

/** 全部动作，新的在前；`now` 是"现在"的毫秒数。 */
export function buildPendingActions(now: number): PendingAction[] {
  return SPECS.map((spec) => toAction(spec, now));
}

/** 等待回应的动作数（导航徽标、概览）。 */
export const PENDING_ACTION_COUNT = SPECS.filter((s) => !s.resolved).length;
