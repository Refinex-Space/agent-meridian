/*
 * 来源：shadcn/ui new-york-v4 `button`（ui.shadcn.com/r，2026-11 取用）。与上游的差异：
 * - 颜色、圆角换成本项目令牌：rounded-control（4px）；焦点环由全局 :focus-visible 给出（2px 实线海图蓝），去掉 ring 光晕；
 * - 变体改为 primary、secondary、ghost、danger；去掉 link、outline 的阴影与 dark: 分支（令牌自带亮暗两套）；
 * - 悬停只换底色，不上浮、不缩放；禁用态用 ink-disabled 与 surface-inset，不用整体半透明；
 * - 尺寸只有 sm（28px）与 md（32px）及其图标按钮；文字 13px、500 字重；
 * - 新增 `pending`：按钮内显示进度并禁止重复提交（frontend-design.md「加载」）。
 */
import { cva, type VariantProps } from 'class-variance-authority';
import { Slot } from 'radix-ui';
import type { ComponentProps, ReactNode } from 'react';

import { cn } from '@/lib/cn';

import { CircleNotchIcon } from './glyphs';
import { Icon } from './icon';

const buttonVariants = cva(
  [
    'inline-flex shrink-0 items-center justify-center gap-2 rounded-control text-13 font-medium whitespace-nowrap select-none',
    'transition-colors duration-150 ease-out',
    'disabled:cursor-not-allowed aria-busy:cursor-progress',
  ],
  {
    variants: {
      variant: {
        primary:
          'bg-accent text-on-accent hover:bg-accent-hover disabled:bg-surface-inset disabled:text-ink-disabled',
        secondary:
          'border border-rule-strong/70 bg-surface text-ink hover:border-rule-strong hover:bg-surface-hover disabled:border-rule disabled:bg-surface disabled:text-ink-disabled',
        ghost:
          'text-ink-2 hover:bg-surface-hover hover:text-ink disabled:bg-transparent disabled:text-ink-disabled',
        danger:
          'bg-danger text-on-accent hover:bg-danger-hover disabled:bg-surface-inset disabled:text-ink-disabled',
      },
      size: {
        sm: 'h-7 px-2',
        md: 'h-8 px-3',
        'icon-sm': 'size-7',
        'icon-md': 'size-8',
      },
    },
    defaultVariants: { variant: 'secondary', size: 'md' },
  },
);

export interface ButtonProps extends ComponentProps<'button'>, VariantProps<typeof buttonVariants> {
  /** 由子元素（例如链接）承担按钮的样式与行为。 */
  asChild?: boolean;
  /** 操作进行中：显示进度、禁用按钮，文字保持不变。 */
  pending?: boolean;
  /** 文字前的图标。 */
  icon?: ReactNode;
}

/**
 * 按钮：主操作用 primary，一页通常只有一个；危险操作用 danger，必须配合写明后果的确认。
 */
export function Button({
  className,
  variant,
  size,
  asChild = false,
  pending = false,
  disabled,
  icon,
  children,
  ...props
}: ButtonProps) {
  const Comp = asChild ? Slot.Root : 'button';
  const leading = pending ? <Icon glyph={CircleNotchIcon} className="motion-safe:animate-spin" /> : icon;
  return (
    <Comp
      data-slot="button"
      className={cn(buttonVariants({ variant, size }), className)}
      disabled={disabled === true || pending}
      aria-busy={pending || undefined}
      {...props}
    >
      {asChild ? (
        children
      ) : (
        <>
          {leading}
          {children}
        </>
      )}
    </Comp>
  );
}

export { buttonVariants };
