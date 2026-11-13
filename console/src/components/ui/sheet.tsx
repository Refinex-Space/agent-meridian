/*
 * 来源：shadcn/ui new-york-v4 `sheet`（shadcn CLI 4.23.0 生成，2026-11-13），即控制台的"抽屉"。与上游的差异：
 * - 只保留左右两侧（上下抽屉在控制台没有用处）；默认宽 480px（`size` 可选 md 480、lg 640），全高、贴边一侧有 1px 刻线，
 *   不加圆角；surface-raised 底与唯一的浮层阴影；遮罩用 scrim 令牌；
 * - 滑入 250ms ease-out、滑出 200ms ease-in（上游 500ms 与 300ms），减少动效时只有不透明度；动画不依赖 tw-animate-css；
 * - 结构改为固定的三段：头部 48px（标题与关闭按钮同行）、可滚动的主体（可用键盘聚焦）、底部操作栏（上方一条刻线，按钮靠右）；
 * - 关闭按钮用 Button 的 ghost 图标按钮与 Phosphor 的 X，可访问名称"关闭"；去掉 "use client"。
 */
import { Dialog as SheetPrimitive } from 'radix-ui';
import type { ComponentProps } from 'react';

import { cn } from '@/lib/cn';

import { Button } from './button';
import { XIcon } from './glyphs';
import { Icon } from './icon';

/** 抽屉：在不离开当前页面的情况下查看或编辑一个对象（例如列表中的一行）。 */
export function Sheet(props: ComponentProps<typeof SheetPrimitive.Root>) {
  return <SheetPrimitive.Root data-slot="sheet" {...props} />;
}

export function SheetTrigger(props: ComponentProps<typeof SheetPrimitive.Trigger>) {
  return <SheetPrimitive.Trigger data-slot="sheet-trigger" {...props} />;
}

export function SheetClose(props: ComponentProps<typeof SheetPrimitive.Close>) {
  return <SheetPrimitive.Close data-slot="sheet-close" {...props} />;
}

function SheetOverlay({ className, ...props }: ComponentProps<typeof SheetPrimitive.Overlay>) {
  return (
    <SheetPrimitive.Overlay
      data-slot="sheet-overlay"
      className={cn(
        'fixed inset-0 z-50 bg-scrim data-[state=closed]:animate-fade-out data-[state=open]:animate-fade-in',
        className,
      )}
      {...props}
    />
  );
}

const SHEET_WIDTH = { md: 'w-120', lg: 'w-160' } as const;

export interface SheetContentProps extends ComponentProps<typeof SheetPrimitive.Content> {
  side?: 'right' | 'left';
  size?: keyof typeof SHEET_WIDTH;
}

/** 抽屉面板：从右侧（默认）或左侧滑入。 */
export function SheetContent({
  className,
  children,
  side = 'right',
  size = 'md',
  ...props
}: SheetContentProps) {
  return (
    <SheetPrimitive.Portal>
      <SheetOverlay />
      <SheetPrimitive.Content
        data-slot="sheet-content"
        className={cn(
          'shadow-overlay fixed inset-y-0 z-50 flex max-w-[calc(100vw-2rem)] flex-col bg-surface-raised text-ink outline-none',
          SHEET_WIDTH[size],
          side === 'right'
            ? 'right-0 border-l border-rule motion-safe:data-[state=closed]:animate-sheet-out-right motion-safe:data-[state=open]:animate-sheet-in-right'
            : 'left-0 border-r border-rule motion-safe:data-[state=closed]:animate-sheet-out-left motion-safe:data-[state=open]:animate-sheet-in-left',
          'motion-reduce:data-[state=closed]:animate-fade-out motion-reduce:data-[state=open]:animate-fade-in',
          className,
        )}
        {...props}
      >
        {children}
      </SheetPrimitive.Content>
    </SheetPrimitive.Portal>
  );
}

/** 头部：标题在左、关闭按钮在右，下方一条刻线。 */
export function SheetHeader({ className, children, ...props }: ComponentProps<'div'>) {
  return (
    <div
      data-slot="sheet-header"
      className={cn(
        'flex min-h-12 shrink-0 items-center gap-2 border-b border-rule py-2 pr-2 pl-6',
        className,
      )}
      {...props}
    >
      <div className="flex min-w-0 flex-1 flex-col gap-1">{children}</div>
      <SheetPrimitive.Close asChild>
        <Button variant="ghost" size="icon-sm" aria-label="关闭">
          <Icon glyph={XIcon} />
        </Button>
      </SheetPrimitive.Close>
    </div>
  );
}

export interface SheetBodyProps extends ComponentProps<'div'> {
  /** 滚动区域的可访问名称，默认"抽屉内容"。 */
  label?: string;
}

/** 可滚动的主体：可用键盘聚焦后滚动（role="region"）。 */
export function SheetBody({ className, label = '抽屉内容', ...props }: SheetBodyProps) {
  return (
    <div
      data-slot="sheet-body"
      role="region"
      aria-label={label}
      tabIndex={0}
      className={cn('min-h-0 flex-1 overflow-y-auto p-6', className)}
      {...props}
    />
  );
}

/** 底部操作栏：按钮靠右，主操作在最右。 */
export function SheetFooter({ className, ...props }: ComponentProps<'div'>) {
  return (
    <div
      data-slot="sheet-footer"
      className={cn('flex shrink-0 justify-end gap-2 border-t border-rule px-6 py-3', className)}
      {...props}
    />
  );
}

export function SheetTitle({ className, ...props }: ComponentProps<typeof SheetPrimitive.Title>) {
  return (
    <SheetPrimitive.Title
      data-slot="sheet-title"
      className={cn('truncate text-16 leading-heading font-semibold', className)}
      {...props}
    />
  );
}

export function SheetDescription({ className, ...props }: ComponentProps<typeof SheetPrimitive.Description>) {
  return (
    <SheetPrimitive.Description
      data-slot="sheet-description"
      className={cn('text-13 text-ink-2', className)}
      {...props}
    />
  );
}
