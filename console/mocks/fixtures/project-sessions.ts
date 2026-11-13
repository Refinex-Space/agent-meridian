/**
 * 原型项目"华东经营分析"最近 7 天的会话（会话列表原型）。时间相对"现在"排布，相对时间在任何时候打开都合理；
 * 同一个种子与同一个"现在"总是生成同样的列表。等待处理的会话就是待处理夹具中那几个动作所在的会话（其中有会话详情原型里那段
 * "第三季度销售分析"），其余会话运行中、空闲或已归档。
 */
import type { Session, SessionStatus } from '@/lib/api/types';

import {
  AGENT_CONTRACT,
  AGENT_POLICY,
  AGENT_SERVICE_QA,
  AGENT_STORE_PATROL,
  AGENT_WEEKLY,
  PROJECT_AGENTS,
} from './agents';
import { buildPendingActions } from './pending-actions';
import { Q3_AGENT, Q3_SESSION_ID } from './q3-session';

/** 智能体与它的会话占比、终端用户的写法。 */
const MIX = [
  { agent: Q3_AGENT, weight: 5, user: (n: number) => `u_${8000 + (n % 400)}` },
  { agent: AGENT_SERVICE_QA, weight: 7, user: (n: number) => `qa-batch-${2026111 + (n % 9)}` },
  { agent: AGENT_POLICY, weight: 4, user: (n: number) => `u_${1000 + (n % 900)}` },
  {
    agent: AGENT_STORE_PATROL,
    weight: 4,
    user: (n: number) => `store-${String(201 + (n % 60)).padStart(4, '0')}`,
  },
  { agent: AGENT_CONTRACT, weight: 2, user: (n: number) => `u_${3000 + (n % 300)}` },
  { agent: AGENT_WEEKLY, weight: 1, user: () => 'scheduler' },
] as const;

const TOTAL_WEIGHT = MIX.reduce((sum, m) => sum + m.weight, 0);

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

const CROCKFORD = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';

function ulidTail(next: () => number, length: number): string {
  return Array.from({ length }, () => CROCKFORD[Math.floor(next() * 32)]).join('');
}

function pick(next: () => number) {
  let r = next() * TOTAL_WEIGHT;
  for (const m of MIX) {
    r -= m.weight;
    if (r < 0) return m;
  }
  return MIX[0];
}

/** 越新的会话越可能还在进行；一天以前的会话都已结束或归档。等待处理的会话只来自待处理夹具。 */
function statusOf(ageMs: number, next: () => number): SessionStatus {
  const r = next();
  if (ageMs < 60 * 60_000) {
    if (r < 0.45) return 'running';
    if (r < 0.5) return 'rescheduled';
    return 'idle';
  }
  if (ageMs < 86_400_000) return r < 0.25 ? 'terminated' : 'idle';
  return r < 0.55 ? 'terminated' : 'idle';
}

function session(
  id: string,
  agent: typeof Q3_AGENT,
  endUser: string,
  status: SessionStatus,
  created: number,
  updated: number,
  lastSeq: number,
): Session {
  return {
    id,
    agent_id: agent.id,
    agent_version_id: agent.current_version.id,
    environment_id: 'env_01JBZ7Q3M8T2V6X0B4D9F5H1KN',
    environment_revision: 3,
    end_user_id: endUser,
    end_user_attributes: {},
    interaction: agent.id === AGENT_WEEKLY.id ? 'unattended' : 'attended',
    status,
    metadata: {},
    custom_tools: [],
    last_seq: lastSeq,
    created_at: new Date(created).toISOString(),
    updated_at: new Date(updated).toISOString(),
    terminated_at: status === 'terminated' ? new Date(updated).toISOString() : null,
    deleted_at: null,
  };
}

/** 生成 `count` 个会话，按更新时间倒序；`now` 是"现在"的毫秒数。 */
export function buildProjectSessions(now: number, count = 148, seed = 11): Session[] {
  const next = random(seed);
  const waiting = buildPendingActions(now)
    .filter((a) => a.status === 'pending')
    .map((a) => {
      const agent = PROJECT_AGENTS.find((p) => p.id === a.agent_id) ?? Q3_AGENT;
      const mix = MIX.find((m) => m.agent.id === agent.id) ?? MIX[0];
      const updated = Date.parse(a.created_at);
      const endUser = a.session_id === Q3_SESSION_ID ? 'u_8231' : mix.user(Math.floor(next() * 10_000));
      return session(a.session_id, agent, endUser, 'requires_action', updated - 7 * 60_000, updated, 61);
    });
  const rows: Session[] = [...waiting];
  let updated = now - 95_000;
  for (let i = waiting.length; i < count; i += 1) {
    const mix = pick(next);
    // 最近的 6 个会话正在执行（其中一个被置回队列等待重新执行），像一个工作日上午的项目。
    const recent = i - waiting.length;
    const status: SessionStatus =
      recent < 6 ? (recent === 4 ? 'rescheduled' : 'running') : statusOf(now - updated, next);
    const created = updated - Math.floor(60_000 + next() * 2_400_000);
    rows.push(
      session(
        `ses_01JC${ulidTail(next, 22)}`,
        mix.agent,
        mix.user(Math.floor(next() * 10_000)),
        status,
        created,
        updated,
        Math.floor(8 + next() * 320),
      ),
    );
    // 正在执行的会话间隔 20 秒到 3 分钟；其余在 1 到 90 分钟之间，工作时间更密。
    updated -=
      recent < 6 ? Math.floor(20_000 + next() * 160_000) : Math.floor(60_000 + next() * next() * 5_400_000);
  }
  return rows.sort((a, b) => Date.parse(b.updated_at) - Date.parse(a.updated_at));
}
