/**
 * 会话详情原型的几种情形，都基于同一段"第三季度销售分析"会话（fixtures/q3-session.ts）。
 */
import { delay, http, HttpResponse, type RequestHandler } from 'msw';

import type { SessionEvent } from '@/lib/api/types';

import {
  buildQ3Scenario,
  Q3_AGENT,
  Q3_FIXED_START,
  Q3_SESSION_ID,
  q3Session,
  type Q3Scenario,
  type StreamStep,
} from '../fixtures/q3-session';
import { scriptedSessionHandlers } from './scripted-session';

export type Q3Mode = 'live' | 'awaiting' | 'finished' | 'loading' | 'error';

/** 以"现在"为终点重新排布时间：让最后一个历史事件发生在 `endsAgo` 毫秒之前。 */
function scenarioEndingAgo(pick: (s: Q3Scenario) => readonly SessionEvent[], endsAgo: number): Q3Scenario {
  const probe = buildQ3Scenario(Q3_FIXED_START);
  const last = pick(probe).at(-1);
  const span = last ? Date.parse(last.created_at) - Q3_FIXED_START.getTime() : 0;
  return buildQ3Scenario(new Date(Date.now() - endsAgo - span));
}

/** 把一段事件变成推送脚本：保留先后顺序，间隔压缩到 0.4 至 1.6 秒，便于观看。 */
function toScript(events: readonly SessionEvent[], firstDelay: number): StreamStep[] {
  return events.map((event, i) => {
    const previous = events[i - 1];
    const gap = previous ? Date.parse(event.created_at) - Date.parse(previous.created_at) : firstDelay;
    return { delay: i === 0 ? firstDelay : Math.min(1600, Math.max(400, gap)), event };
  });
}

export function q3Handlers(mode: Q3Mode): RequestHandler[] {
  if (mode === 'error') {
    return [
      http.get(`/v1/sessions/${Q3_SESSION_ID}`, async () => {
        await delay(300);
        return HttpResponse.json(
          {
            type: 'urn:meridian:error:session.not_found',
            title: '会话不存在',
            status: 404,
            detail: '会话不存在，或不属于当前项目。',
            instance: `/v1/sessions/${Q3_SESSION_ID}`,
            code: 'session.not_found',
            request_id: 'req_01JCQB7Y3M6P9S2V5X8A1D4G7K',
          },
          { status: 404, headers: { 'Content-Type': 'application/problem+json' } },
        );
      }),
    ];
  }

  if (mode === 'finished') {
    const scenario = buildQ3Scenario(Q3_FIXED_START);
    const continuation = scenario.afterResponse(true).flatMap((s) => ('event' in s ? [s.event] : []));
    const history = [...scenario.turnOne, ...scenario.turnTwo, ...continuation];
    return scriptedSessionHandlers({
      session: q3Session('idle', history.at(-1)?.seq ?? 0, scenario.startedAt),
      agent: Q3_AGENT,
      history,
      liveTimestamps: false,
    });
  }

  if (mode === 'awaiting') {
    const scenario = scenarioEndingAgo((s) => [...s.turnOne, ...s.turnTwo], 40_000);
    const history = [...scenario.turnOne, ...scenario.turnTwo];
    return scriptedSessionHandlers({
      session: q3Session('requires_action', history.at(-1)?.seq ?? 0, scenario.startedAt),
      agent: Q3_AGENT,
      history,
      onRespond: (_actionId, approved) => scenario.afterResponse(approved),
    });
  }

  // live 与 loading：第 1 轮已在一分钟前结束，打开页面后第 2 轮实时推送，停在等待批准。
  const scenario = scenarioEndingAgo((s) => s.turnOne, 65_000);
  return scriptedSessionHandlers({
    session: q3Session('idle', scenario.turnOne.at(-1)?.seq ?? 0, scenario.startedAt),
    agent: Q3_AGENT,
    history: scenario.turnOne,
    script: toScript(scenario.turnTwo, 1500),
    onRespond: (_actionId, approved) => scenario.afterResponse(approved),
    ...(mode === 'loading' ? { loadDelay: 'infinite' as const } : {}),
  });
}
