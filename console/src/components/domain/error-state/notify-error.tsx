import { notify } from '@/components/ui/sonner';

import { CopyableId } from '../copyable-id';

import { describeError } from './describe-error';

/**
 * 操作失败且结果不在视野里时发错误通知（frontend-design.md「通知策略」）：不自动消失、带关闭按钮，写明原因与下一步，
 * 附可复制的请求 ID。结果就在视野里的失败（表单提交、行内操作）就地显示 `ErrorState`，不发通知。
 *
 * @param title 发生了什么，按操作写（"发布失败"）；不传时按 HTTP 状态给出。
 */
export function notifyError(error: unknown, title?: string): string | number {
  const described = describeError(error);
  return notify.error(title ?? described.title, {
    description: (
      <span className="flex flex-col gap-1">
        {described.detail && <span className="text-ink">{described.detail}</span>}
        <span>{described.next}</span>
        {described.requestId && (
          <span className="flex items-center gap-2">
            请求 ID
            <CopyableId id={described.requestId} label="请求 ID" full />
          </span>
        )}
      </span>
    ),
  });
}
