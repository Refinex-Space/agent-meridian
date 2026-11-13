import { BudgetBanner as Banner } from '@/app/shell/budget-banner';

import { CURRENT_PROJECT_ID, type BudgetAlert } from '../fixtures/identity';

export interface BudgetBannerProps {
  alert: BudgetAlert;
}

const HARD = 1_000_000;

/** 原型的预算告警：把视图模型换成 `GET P:/budget-status` 的结构，交给正式的横幅。 */
export function BudgetBanner({ alert }: BudgetBannerProps) {
  return (
    <Banner
      projectId={CURRENT_PROJECT_ID}
      status={{
        project_id: CURRENT_PROJECT_ID,
        period: '2026-11',
        period_start: '2026-11-01T00:00:00Z',
        period_end: '2026-12-01T00:00:00Z',
        used_tokens: Math.round(alert.usedRatio * HARD),
        soft_limit_tokens: Math.round(alert.softRatio * HARD),
        hard_limit_tokens: HARD,
        limit_source: 'project',
        state: alert.state,
      }}
    />
  );
}
