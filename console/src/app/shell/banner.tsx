import type { ReactNode } from 'react';

import { StatusGlyph } from '@/components/domain/status-badge';
import { Button } from '@/components/ui/button';
import { XIcon } from '@/components/ui/glyphs';
import { Icon } from '@/components/ui/icon';
import { cn } from '@/lib/cn';

export interface BannerProps {
  /** brass：需要注意（超过提醒阈值）；danger：功能受影响（预算用完、与服务端断开）。 */
  tone: 'brass' | 'danger';
  children: ReactNode;
  /** 右侧的操作（链接或按钮），颜色随语气。 */
  action?: ReactNode;
  /** 关闭按钮的可访问名称，例如"关闭预算提醒"。 */
  dismissLabel: string;
  onDismiss: () => void;
}

/**
 * 页面顶部的横幅（frontend-design.md「通知策略」：影响整个应用的状态），在顶栏之下、内容之上，一行写完，可关闭。
 */
export function Banner({ tone, children, action, dismissLabel, onDismiss }: BannerProps) {
  const danger = tone === 'danger';
  return (
    <div
      role="status"
      className={cn(
        'flex h-10 shrink-0 items-center gap-3 border-b px-6 text-13',
        danger ? 'border-danger/40 bg-danger-weak' : 'border-brass/40 bg-brass-weak',
      )}
    >
      <StatusGlyph kind={danger ? 'failure' : 'requires_action'} />
      <p className="min-w-0 flex-1 truncate text-ink">{children}</p>
      {action}
      <Button size="icon-sm" variant="ghost" aria-label={dismissLabel} onClick={onDismiss}>
        <Icon glyph={XIcon} />
      </Button>
    </div>
  );
}

/** 横幅里的操作：链接样式的文字，颜色随语气。 */
export function bannerActionClass(tone: BannerProps['tone']): string {
  return cn('shrink-0 font-medium hover:underline', tone === 'danger' ? 'text-danger' : 'text-brass-ink');
}
