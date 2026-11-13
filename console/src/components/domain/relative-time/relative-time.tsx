import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import { cn } from '@/lib/cn';
import { formatInstant, formatRelative } from '@/lib/format';
import { useNow } from '@/lib/use-now';

export interface RelativeTimeProps {
  /** ISO 时间。 */
  value: string;
  /** 当前时间（毫秒）；不传时用共享时钟，每 30 秒刷新。故事与测试用它固定结果。 */
  now?: number;
  className?: string;
}

/**
 * 列表中的相对时间（"5 分钟前"），悬停显示带毫秒与时区的绝对时间；`<time>` 带机器可读的 dateTime。
 * 详情中显示绝对时间，不用这个组件。
 */
export function RelativeTime({ value, now, className }: RelativeTimeProps) {
  const clock = useNow();
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <time
          dateTime={value}
          data-slot="relative-time"
          className={cn('whitespace-nowrap tabular-nums', className)}
        >
          {formatRelative(value, now ?? clock)}
        </time>
      </TooltipTrigger>
      <TooltipContent>{formatInstant(value)}</TooltipContent>
    </Tooltip>
  );
}
