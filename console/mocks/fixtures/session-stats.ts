/**
 * 原型项目的运行概况（公开 API 的 SessionStats，`GET /v1/session-stats`）。会话计数从会话列表夹具数出来，
 * 待处理动作数取待处理夹具，失败率按时间窗给出（门店巡检报告这周因沙箱内存不足失败偏多），最近错误用 runtime 真实的错误码。
 */
import type { Schemas, Session } from '@/lib/api/types';

import {
  AGENT_CONTRACT,
  AGENT_POLICY,
  AGENT_SERVICE_QA,
  AGENT_STORE_PATROL,
  AGENT_WEEKLY,
  PROJECT_AGENTS,
} from './agents';
import { PENDING_ACTION_COUNT } from './pending-actions';
import { Q3_AGENT_ID } from './q3-session';

type Window = Schemas['StatsWindow'];

/** 每个智能体在三个时间窗内结束的轮次与失败轮次。 */
const TURNS: Record<string, Record<Window, [number, number]>> = {
  [Q3_AGENT_ID]: { '1h': [7, 0], '24h': [86, 3], '7d': [512, 11] },
  [AGENT_SERVICE_QA.id]: { '1h': [31, 0], '24h': [412, 2], '7d': [2_874, 9] },
  [AGENT_POLICY.id]: { '1h': [9, 0], '24h': [133, 0], '7d': [906, 2] },
  [AGENT_STORE_PATROL.id]: { '1h': [4, 1], '24h': [64, 9], '7d': [431, 37] },
  [AGENT_CONTRACT.id]: { '1h': [1, 0], '24h': [21, 1], '7d': [118, 4] },
  [AGENT_WEEKLY.id]: { '1h': [0, 0], '24h': [1, 0], '7d': [6, 0] },
};

const WINDOW_MS: Record<Window, number> = { '1h': 3_600_000, '24h': 86_400_000, '7d': 7 * 86_400_000 };

interface ErrorSpec {
  agent: string;
  code: string;
  message: string;
  /** 发生于多少毫秒之前。 */
  ago: number;
}

const MIN = 60_000;

const ERRORS: ErrorSpec[] = [
  {
    agent: AGENT_STORE_PATROL.id,
    code: 'sandbox.unavailable',
    message: '会话沙箱内存超出上限 2 GiB，进程被终止；照片批量缩放时一次载入了 186 张原图。',
    ago: 26 * MIN,
  },
  {
    agent: AGENT_STORE_PATROL.id,
    code: 'sandbox.unavailable',
    message: '会话沙箱内存超出上限 2 GiB，进程被终止。',
    ago: 2.2 * 60 * MIN,
  },
  {
    agent: Q3_AGENT_ID,
    code: 'model.rate_limit_timeout',
    message: 'qwen-plus 的供应商账号触发限流，等待 30 秒后仍未取得配额。',
    ago: 3.4 * 60 * MIN,
  },
  {
    agent: AGENT_SERVICE_QA.id,
    code: 'tool.mcp_unavailable',
    message: 'MCP 服务 crm 连接超时（10 秒），工单系统可能在维护。',
    ago: 5.1 * 60 * MIN,
  },
  {
    agent: AGENT_STORE_PATROL.id,
    code: 'turn.recovery_exhausted',
    message: '本轮在节点下线后重新调度了 3 次，恢复次数已用尽。',
    ago: 7.8 * 60 * MIN,
  },
  {
    agent: AGENT_CONTRACT.id,
    code: 'model.provider_unavailable',
    message: '模型供应商返回 503，备用模型同样不可用。',
    ago: 9.6 * 60 * MIN,
  },
  {
    agent: Q3_AGENT_ID,
    code: 'model.budget_exceeded',
    message: '会话级预算 50 万 token 已用完。',
    ago: 13 * 60 * MIN,
  },
  {
    agent: AGENT_SERVICE_QA.id,
    code: 'tool.mcp_unavailable',
    message: 'MCP 服务 crm 返回 502。',
    ago: 19.5 * 60 * MIN,
  },
  {
    agent: AGENT_STORE_PATROL.id,
    code: 'sandbox.unavailable',
    message: '会话沙箱内存超出上限 2 GiB，进程被终止。',
    ago: 2.6 * 24 * 60 * MIN,
  },
];

function id(prefix: string, seed: number): string {
  const CROCKFORD = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';
  let x = seed * 2654435761;
  let out = '';
  for (let i = 0; i < 22; i += 1) {
    x = (x * 1103515245 + 12345) >>> 0;
    out += CROCKFORD[x % 32];
  }
  return `${prefix}_01JC${out}`;
}

/** 按时间窗生成运行概况；`sessions` 是会话列表夹具，`now` 是"现在"的毫秒数。 */
export function buildSessionStats(
  window: Window,
  now: number,
  sessions: readonly Session[],
): Schemas['SessionStats'] {
  const live = (s: Session) => s.status === 'running' || s.status === 'rescheduled';
  const windowStart = new Date(now - WINDOW_MS[window]);
  windowStart.setMinutes(0, 0, 0);
  return {
    window,
    window_start: windowStart.toISOString(),
    generated_at: new Date(now).toISOString(),
    stats_updated_at: new Date(now - 42_000).toISOString(),
    running_sessions: sessions.filter(live).length,
    requires_action_sessions: sessions.filter((s) => s.status === 'requires_action').length,
    pending_actions: PENDING_ACTION_COUNT,
    agents: PROJECT_AGENTS.map((agent) => {
      const [turns, failed] = TURNS[agent.id]?.[window] ?? [0, 0];
      const mine = sessions.filter((s) => s.agent_id === agent.id);
      return {
        agent_id: agent.id,
        active_sessions: mine.filter((s) => s.status !== 'terminated').length,
        running_sessions: mine.filter(live).length,
        turns,
        failed_turns: failed,
        failure_rate: turns === 0 ? null : failed / turns,
      };
    }).sort((a, b) => a.agent_id.localeCompare(b.agent_id)),
    recent_errors: ERRORS.filter((e) => e.ago <= WINDOW_MS[window])
      .slice(0, 10)
      .map((e, i) => ({
        session_id: id('ses', i + 3),
        thread_id: id('thr', i + 3),
        turn_id: id('trn', i + 3),
        agent_id: e.agent,
        code: e.code,
        message: e.message,
        occurred_at: new Date(now - e.ago).toISOString(),
      })),
  };
}
