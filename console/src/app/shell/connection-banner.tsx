import { useState, useSyncExternalStore } from 'react';

import { getConnectionState, subscribeConnection } from '@/lib/api/connection';

import { Banner, bannerActionClass } from './banner';

export interface ConnectionBannerProps {
  /** 立即重新请求当前页面的数据。 */
  onRetry: () => void;
}

/**
 * 与服务端断开的横幅：请求因网络或网关不可用失败时出现，任一请求恢复成功后自动消失；关闭后，下一次断开时再出现。
 */
export function ConnectionBanner({ onRetry }: ConnectionBannerProps) {
  const state = useSyncExternalStore(subscribeConnection, getConnectionState);
  const [dismissed, setDismissed] = useState(false);
  const [seen, setSeen] = useState(state);
  if (state !== seen) {
    // 恢复连接后重置关闭状态，下一次断开时重新提示。
    setSeen(state);
    if (state === 'online') setDismissed(false);
  }
  if (state === 'online' || dismissed) return null;
  return (
    <Banner
      tone="danger"
      dismissLabel="关闭连接提醒"
      onDismiss={() => setDismissed(true)}
      action={
        <button type="button" onClick={onRetry} className={bannerActionClass('danger')}>
          重新连接
        </button>
      }
    >
      与服务端的连接已断开，页面上的数据可能不是最新的。
    </Banner>
  );
}
