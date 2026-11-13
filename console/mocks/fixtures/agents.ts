/**
 * 原型项目"华东经营分析"中的智能体（公开 API 的 Agent，`GET /v1/agents`）。会话列表、待处理动作、概览按 ID 显示它们的名字；
 * 智能体页面的管理数据（版本、规格、运行情况）是 `src/prototypes/fixtures/` 中的视图模型。
 */
import type { Schemas } from '@/lib/api/types';

import { Q3_AGENT } from './q3-session';

type Agent = Schemas['Agent'];

function agent(
  id: string,
  name: string,
  description: string,
  version: number,
  publishedAt: string,
  createdAt: string,
): Agent {
  return {
    id,
    name,
    description,
    current_version: {
      id: `agv_${id.slice(4, 14)}V${String(version).padStart(2, '0')}`,
      version,
      published_at: publishedAt,
    },
    created_at: createdAt,
    updated_at: publishedAt,
  };
}

export const AGENT_SERVICE_QA = agent(
  'agt_01JBX5K7N2Q8T4W1Y6B3D9F0HM',
  '客服质检',
  '按质检规范给客服通话转写打分，标出违规话术',
  7,
  '2026-11-06T06:15:00.000Z',
  '2026-08-19T02:40:00.000Z',
);

export const AGENT_POLICY = agent(
  'agt_01JBX6P3R9V5X1A7C2E8G4J0KN',
  '政策解读',
  '回答差旅、采购、费用报销制度的问题，给出条款出处',
  4,
  '2026-10-28T09:02:00.000Z',
  '2026-09-11T07:25:00.000Z',
);

export const AGENT_STORE_PATROL = agent(
  'agt_01JBX7S1T6W2Z8B4D0F5H1K7MP',
  '门店巡检报告',
  '汇总门店巡检照片与打分表，生成整改清单',
  9,
  '2026-11-12T01:30:00.000Z',
  '2026-07-30T05:10:00.000Z',
);

export const AGENT_CONTRACT = agent(
  'agt_01JBX8V4X0A6C2E8G4J0L6N2QR',
  '合同条款审查',
  '对照采购合同模板检查付款、违约与保密条款',
  3,
  '2026-11-03T03:45:00.000Z',
  '2026-10-09T08:00:00.000Z',
);

export const AGENT_WEEKLY = agent(
  'agt_01JBX9Y7Z3B9D5F1H7K3M9P5SV',
  '销售周报',
  '每周一汇总上周各门店销售与库存，生成周报',
  15,
  '2026-11-11T00:20:00.000Z',
  '2026-06-24T01:05:00.000Z',
);

/** 项目中的全部智能体，按名字排序（与 `GET /v1/agents` 的默认顺序一致）。 */
export const PROJECT_AGENTS: readonly Agent[] = [
  Q3_AGENT,
  AGENT_SERVICE_QA,
  AGENT_POLICY,
  AGENT_STORE_PATROL,
  AGENT_CONTRACT,
  AGENT_WEEKLY,
];

/** 智能体 ID 到名字。 */
export const AGENT_NAMES: Readonly<Record<string, string>> = Object.fromEntries(
  PROJECT_AGENTS.map((a) => [a.id, a.name]),
);
