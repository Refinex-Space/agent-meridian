import { Button } from '@/components/ui/button';
import { ArrowClockwiseIcon, WarningCircleIcon } from '@/components/ui/glyphs';
import { Icon } from '@/components/ui/icon';
import { cn } from '@/lib/cn';

import { CopyableId } from '../copyable-id';
import { StateLayout } from '../empty-state/state-layout';

import { describeError } from './describe-error';

export interface ErrorStateProps {
  /** 请求错误：ApiError、problem+json 对象，或 fetch 抛出的错误。 */
  error: unknown;
  /** 发生了什么，按区域写（"无法加载会话列表"）；不传时按 HTTP 状态给出。 */
  title?: string;
  /** 给出时显示"重试"。 */
  onRetry?: () => void;
  /** 重试进行中：按钮内显示进度。 */
  retrying?: boolean;
  /** region：数据区域中央；inline：面板、对话框或表单里的一块。 */
  size?: 'region' | 'inline';
  className?: string;
}

/**
 * problem+json 错误：写明发生了什么、原因与下一步，附可复制的请求 ID 与重试。区域加载失败时只占该区域，页面其他部分照常可用。
 */
export function ErrorState({
  error,
  title,
  onRetry,
  retrying = false,
  size = 'region',
  className,
}: ErrorStateProps) {
  const described = describeError(error);
  return (
    <StateLayout
      size={size}
      role="alert"
      className={cn(size === 'inline' && 'rounded-panel bg-danger-weak p-3', className)}
      figure={<Icon glyph={WarningCircleIcon} size={size === 'inline' ? 16 : 24} className="text-danger" />}
      title={title ?? described.title}
      actions={
        onRetry && (
          <Button
            size={size === 'inline' ? 'sm' : 'md'}
            pending={retrying}
            icon={<Icon glyph={ArrowClockwiseIcon} />}
            onClick={onRetry}
          >
            重试
          </Button>
        )
      }
    >
      {described.detail && <p className={cn(size === 'inline' && 'text-ink')}>{described.detail}</p>}
      {described.violations && described.violations.length > 0 && (
        <ul className={cn('flex flex-col gap-1', size === 'region' && 'text-left')}>
          {described.violations.map((v) => (
            <li key={`${v.field}:${v.message}`}>
              <code className="text-12 text-ink">{v.field}</code> {v.message}
            </li>
          ))}
        </ul>
      )}
      <p>{described.next}</p>
      {described.requestId && (
        <p className="flex items-center gap-2">
          请求 ID
          <CopyableId id={described.requestId} label="请求 ID" full />
        </p>
      )}
    </StateLayout>
  );
}
