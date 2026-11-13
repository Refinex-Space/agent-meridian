import { useState, type MouseEvent } from 'react';

import { StatusGlyph } from '@/components/domain/status-badge';
import { Button } from '@/components/ui/button';
import { XIcon } from '@/components/ui/glyphs';
import { Icon } from '@/components/ui/icon';
import { cn } from '@/lib/cn';

import type { BudgetAlert } from '../fixtures/identity';

export interface BudgetBannerProps {
  alert: BudgetAlert;
}

const stay = (e: MouseEvent) => e.preventDefault();

/**
 * 预算告警横幅（通知策略「影响整个应用的状态」）：超过提醒阈值为黄铜色，达到上限为信号红；可关闭，下次轮询状态变化时再出现。
 */
export function BudgetBanner({ alert }: BudgetBannerProps) {
  const [dismissed, setDismissed] = useState(false);
  if (dismissed || alert.state === 'ok') return null;
  const hard = alert.state === 'hard_exceeded';
  const percent = Math.round(alert.usedRatio * 100);
  return (
    <div
      role="status"
      className={cn(
        'flex h-10 shrink-0 items-center gap-3 border-b px-6 text-13',
        hard ? 'border-danger/40 bg-danger-weak' : 'border-brass/40 bg-brass-weak',
      )}
    >
      <StatusGlyph kind={hard ? 'failure' : 'requires_action'} />
      <p className="min-w-0 flex-1 truncate text-ink">
        {hard
          ? '本月预算已用完，新的模型调用会被拒绝。'
          : `本月预算已用 ${percent}%，超过提醒阈值 ${Math.round(alert.softRatio * 100)}%；用完后新的模型调用会被拒绝。`}
      </p>
      <a
        href="/usage"
        onClick={stay}
        className={cn('shrink-0 font-medium hover:underline', hard ? 'text-danger' : 'text-brass-ink')}
      >
        查看用量与预算
      </a>
      <Button size="icon-sm" variant="ghost" aria-label="关闭预算提醒" onClick={() => setDismissed(true)}>
        <Icon glyph={XIcon} />
      </Button>
    </div>
  );
}
