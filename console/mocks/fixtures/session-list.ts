/**
 * 会话列表的固定数据（DataTable、FilterBar 的故事与测试）：同一个种子总是生成完全相同的列表。
 * 行是公开 API 的 Session，智能体名与 token 用量是列表页从其他接口拼上的显示字段。
 */
import type { Session, SessionStatus } from '@/lib/api/types';

export interface SessionListRow {
  session: Session;
  agentName: string;
  totalTokens: number;
}

/** 列表的"现在"：相对时间都相对它计算。 */
export const SESSION_LIST_NOW = Date.parse('2026-11-13T10:00:00+08:00');

const AGENTS = [
  { id: 'agt_01JB7Q2M9XK4', name: '经营分析助手' },
  { id: 'agt_01JB7R5T1WP8', name: '客服质检' },
  { id: 'agt_01JB7S8V4YQ2', name: '政策解读' },
  { id: 'agt_01JB7T0X6ZR5', name: '门店巡检报告' },
];

const STATUSES: SessionStatus[] = [
  'idle',
  'idle',
  'idle',
  'running',
  'requires_action',
  'terminated',
  'rescheduled',
];

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

function suffix(next: () => number, length: number): string {
  return Array.from({ length }, () => CROCKFORD[Math.floor(next() * 32)]).join('');
}

/** 生成 `count` 个会话，按更新时间倒序（最近的在前）。 */
export function buildSessionList(count: number, seed = 7): SessionListRow[] {
  const next = random(seed);
  const rows: SessionListRow[] = [];
  let updated = SESSION_LIST_NOW - 40_000;
  for (let i = 0; i < count; i += 1) {
    const agent = AGENTS[Math.floor(next() * AGENTS.length)] ?? AGENTS[0]!;
    const status = STATUSES[Math.floor(next() * STATUSES.length)] ?? 'idle';
    const created = updated - Math.floor(next() * 3_600_000);
    rows.push({
      agentName: agent.name,
      totalTokens: Math.floor(next() * next() * 400_000),
      session: {
        id: `ses_01JC${suffix(next, 22)}`,
        agent_id: agent.id,
        agent_version_id: `agv_01JB${suffix(next, 8)}`,
        environment_id: 'env_01JB6PRODRO3',
        environment_revision: 3,
        end_user_id: `u_${1000 + Math.floor(next() * 9000)}`,
        end_user_attributes: {},
        interaction: 'attended',
        status,
        metadata: {},
        custom_tools: [],
        last_seq: Math.floor(next() * 400),
        created_at: new Date(created).toISOString(),
        updated_at: new Date(updated).toISOString(),
        terminated_at: status === 'terminated' ? new Date(updated).toISOString() : null,
        deleted_at: null,
      },
    });
    updated -= Math.floor(30_000 + next() * 900_000);
  }
  return rows;
}
