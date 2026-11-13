import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import type { TokenUsage as Usage } from '@/lib/api/types';
import { cn } from '@/lib/cn';
import { formatInteger, formatMoney, formatTokens } from '@/lib/format';

export interface Cost {
  /** 金额（报表接口返回十进制字符串时由调用方转为数字）。 */
  amount: number;
  /** ISO 4217 币种，例如 CNY。 */
  currency: string;
}

export interface TokenUsageProps {
  usage: Usage;
  /** 成本；没有定价时不传，不显示成本一项。 */
  cost?: Cost | null;
  /** inline：一行带单位的数字（会话头部、行内）；detail：逐项精确值的列表（检查器、详情）。 */
  variant?: 'inline' | 'detail';
  className?: string;
}

interface Item {
  label: string;
  value: number;
}

function items(usage: Usage, all: boolean): Item[] {
  const list: Item[] = [
    { label: '输入', value: usage.input_tokens },
    { label: '输出', value: usage.output_tokens },
    { label: '缓存命中', value: usage.cached_tokens },
  ];
  if (all || usage.cache_creation_tokens > 0)
    list.push({ label: '缓存写入', value: usage.cache_creation_tokens });
  if (all || usage.reasoning_tokens > 0) list.push({ label: '推理', value: usage.reasoning_tokens });
  return list;
}

/** 缩写的数字，悬停给出精确值；读屏直接读精确值。 */
function Abbreviated({ value }: { value: number }) {
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <span>
          <span aria-hidden>{formatTokens(value)}</span>
          <span className="sr-only">{formatInteger(value)} token</span>
        </span>
      </TooltipTrigger>
      <TooltipContent>{formatInteger(value)} token</TooltipContent>
    </Tooltip>
  );
}

/**
 * token 用量与成本：输入、输出、缓存命中（缓存写入、推理有值时才出现）与成本，数字等宽、右对齐。
 * 缓存命中、缓存写入是输入的子集，推理是输出的子集，不与输入输出相加。
 */
export function TokenUsage({ usage, cost, variant = 'inline', className }: TokenUsageProps) {
  if (variant === 'detail') {
    return (
      <dl data-slot="token-usage" className={cn('flex flex-col text-13 tabular-nums', className)}>
        {items(usage, true).map(({ label, value }) => (
          <div key={label} className="flex h-7 items-center justify-between gap-4 border-b border-rule">
            <dt className="text-ink-2">{label}</dt>
            <dd>{formatInteger(value)} token</dd>
          </div>
        ))}
        {cost && (
          <div className="flex h-7 items-center justify-between gap-4">
            <dt className="text-ink-2">成本</dt>
            <dd className="font-medium">{formatMoney(cost.amount, cost.currency)}</dd>
          </div>
        )}
      </dl>
    );
  }
  return (
    <dl data-slot="token-usage" className={cn('flex items-start gap-6 text-12 tabular-nums', className)}>
      {items(usage, false).map(({ label, value }) => (
        <div key={label} className="flex flex-col items-end">
          <dt className="text-ink-2">{label}</dt>
          <dd className="text-14 font-medium text-ink">
            <Abbreviated value={value} />
          </dd>
        </div>
      ))}
      {cost && (
        <div className="flex flex-col items-end">
          <dt className="text-ink-2">成本</dt>
          <dd className="text-14 font-medium text-ink">{formatMoney(cost.amount, cost.currency)}</dd>
        </div>
      )}
    </dl>
  );
}
