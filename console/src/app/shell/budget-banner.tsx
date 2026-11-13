import { Link } from '@tanstack/react-router';
import { useState } from 'react';

import type { ProjectBudgetStatus } from '@/lib/api/types';
import { formatTokens } from '@/lib/format';

import { Banner, bannerActionClass } from './banner';

export interface BudgetBannerProps {
  projectId: string;
  /** `GET P:/budget-status`（轮询 5 分钟）。 */
  status: ProjectBudgetStatus;
}

const percent = (part: number, whole: number) => Math.round((part / whole) * 100);

function message(status: ProjectBudgetStatus): string {
  const { state, used_tokens: used, soft_limit_tokens: soft, hard_limit_tokens: hard } = status;
  if (state === 'hard_exceeded') {
    return status.limit_source === 'none'
      ? '本项目还没有设置预算，新的模型调用会被拒绝。'
      : '本月预算已用完，新的模型调用会被拒绝。';
  }
  if (hard !== null && hard > 0 && soft !== null) {
    return `本月预算已用 ${percent(used, hard)}%，超过提醒阈值 ${percent(soft, hard)}%；用完后新的模型调用会被拒绝。`;
  }
  return `本月已用 ${formatTokens(used)} token，超过提醒阈值 ${formatTokens(soft ?? 0)}。`;
}

/**
 * 预算告警横幅：越过提醒阈值为黄铜色，达到上限为信号红；没有告警（`ok`）时不显示。关闭后，状态变化时再出现。
 */
export function BudgetBanner({ projectId, status }: BudgetBannerProps) {
  const [dismissed, setDismissed] = useState<ProjectBudgetStatus['state'] | null>(null);
  if (status.state === 'ok' || dismissed === status.state) return null;
  const tone = status.state === 'hard_exceeded' ? 'danger' : 'brass';
  return (
    <Banner
      tone={tone}
      dismissLabel="关闭预算提醒"
      onDismiss={() => setDismissed(status.state)}
      action={
        <Link to="/projects/$projectId/usage" params={{ projectId }} className={bannerActionClass(tone)}>
          查看用量与预算
        </Link>
      }
    >
      {message(status)}
    </Banner>
  );
}
