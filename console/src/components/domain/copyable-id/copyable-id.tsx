import { CheckIcon, CopyIcon, WarningCircleIcon } from '@/components/ui/glyphs';
import { Icon } from '@/components/ui/icon';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import { cn } from '@/lib/cn';
import { middleEllipsis } from '@/lib/format';
import { useCopy } from '@/lib/use-copy';

export interface CopyableIdProps {
  /** 带前缀的 ID（ses_…、agt_…、req_…）。 */
  id: string;
  /** 对象的名称，组成可访问名称"复制{label}"，例如"会话 ID""请求 ID"。 */
  label: string;
  /** 不做中间省略（详情页、错误信息里的 request_id）。 */
  full?: boolean;
  className?: string;
}

/**
 * 带前缀 ID 的等宽显示：中间省略、一键复制，复制后图标短暂变为对勾并提示"已复制"。
 * 完整的 ID 在提示里；可访问名称包含可见文字（WCAG 2.5.3）。左右各 -4px 外边距，悬停底色外扩，文字与同列内容左对齐。
 */
export function CopyableId({ id, label, full = false, className }: CopyableIdProps) {
  const { state, copy } = useCopy();
  const shown = full ? id : middleEllipsis(id);
  const glyph = state === 'copied' ? CheckIcon : state === 'failed' ? WarningCircleIcon : CopyIcon;
  const tip = state === 'copied' ? '已复制' : state === 'failed' ? '无法访问剪贴板，请手动选择复制' : id;
  return (
    <>
      <Tooltip>
        <TooltipTrigger asChild>
          <button
            type="button"
            data-slot="copyable-id"
            data-state-copy={state}
            onClick={() => void copy(id)}
            className={cn(
              '-mx-1 inline-flex h-6 max-w-full min-w-0 items-center gap-1 rounded-control px-1 font-mono text-12 text-ink-2',
              'transition-colors duration-150 ease-out hover:bg-surface-hover hover:text-ink',
              className,
            )}
          >
            <span className="sr-only">复制{label}</span> <span className="truncate">{shown}</span>
            <Icon
              glyph={glyph}
              className={cn(state === 'copied' && 'text-success', state === 'failed' && 'text-danger')}
            />
          </button>
        </TooltipTrigger>
        <TooltipContent className={cn(state === 'idle' && 'font-mono break-all')}>{tip}</TooltipContent>
      </Tooltip>
      <span role="status" className="sr-only">
        {state === 'copied' ? `已复制${label}` : state === 'failed' ? '复制失败' : ''}
      </span>
    </>
  );
}
