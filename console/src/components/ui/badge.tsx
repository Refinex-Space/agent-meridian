/*
 * 来源：shadcn/ui new-york-v4 `badge`（ui.shadcn.com/r，2026-11 取用）。与上游的差异：
 * - 不做胶囊：rounded-control（4px），1px 刻线边框，12px、500 字重；
 * - 变体改为语气 `tone`：neutral、accent 只描边，brass、danger 加浅底（需要注意的事项）；
 * - 去掉 focus ring 与 aria-invalid 的样式（徽标不可交互）。
 * 状态（运行中、等待处理……）不用徽标的底色表达，用 StatusGlyph 的形状加文字（frontend-design.md「状态符号」）。
 */
import { cva, type VariantProps } from 'class-variance-authority';
import type { ComponentProps } from 'react';

import { cn } from '@/lib/cn';

const badgeVariants = cva(
  'inline-flex h-5 w-fit shrink-0 items-center gap-1 rounded-control border px-1 text-12 font-medium whitespace-nowrap tabular-nums',
  {
    variants: {
      tone: {
        neutral: 'border-rule text-ink-2',
        accent: 'border-accent/40 text-accent',
        brass: 'border-brass/50 bg-brass-weak text-brass-ink',
        danger: 'border-danger/40 bg-danger-weak text-danger',
      },
    },
    defaultVariants: { tone: 'neutral' },
  },
);

export interface BadgeProps extends ComponentProps<'span'>, VariantProps<typeof badgeVariants> {}

/** 徽标：计数、版本号、档位这类短标签。 */
export function Badge({ className, tone, ...props }: BadgeProps) {
  return <span data-slot="badge" className={cn(badgeVariants({ tone }), className)} {...props} />;
}
