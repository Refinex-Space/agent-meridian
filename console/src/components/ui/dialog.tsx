/*
 * 来源：shadcn/ui new-york-v4 `dialog`（shadcn CLI 4.23.0 生成，2026-11-13）。与上游的差异：
 * - 遮罩用 scrim 令牌（墨色压暗，不模糊），上游 bg-black/50；
 * - 面板：surface-raised 底、1px 刻线、8px 圆角、唯一的浮层阴影，内边距 24px；宽度分 sm 400、md 480、lg 640 三档；
 * - 出现 200ms 不透明度加 2% 缩放（ease-out），退出 150ms（ease-in），减少动效时只有不透明度；动画不依赖 tw-animate-css；
 * - 标题 16px 600 字重、描述 13px 石墨色，头部与底部都左对齐（上游小屏居中）；底部按钮靠右、主操作在最右；
 * - 关闭按钮用 Button 的 ghost 图标按钮与 Phosphor 的 X，可访问名称"关闭"；去掉底部的 showCloseButton（取消按钮由调用方写明动作）；
 * - 新增 DialogBody：长内容的滚动区域，可用键盘聚焦。
 */
import { Dialog as DialogPrimitive } from 'radix-ui';
import type { ComponentProps } from 'react';

import { cn } from '@/lib/cn';

import { Button } from './button';
import { XIcon } from './glyphs';
import { Icon } from './icon';

/** 对话框：需要用户专注完成的短流程。不可逆操作用 DangerConfirmDialog。 */
export function Dialog(props: ComponentProps<typeof DialogPrimitive.Root>) {
  return <DialogPrimitive.Root data-slot="dialog" {...props} />;
}

export function DialogTrigger(props: ComponentProps<typeof DialogPrimitive.Trigger>) {
  return <DialogPrimitive.Trigger data-slot="dialog-trigger" {...props} />;
}

export function DialogPortal(props: ComponentProps<typeof DialogPrimitive.Portal>) {
  return <DialogPrimitive.Portal data-slot="dialog-portal" {...props} />;
}

export function DialogClose(props: ComponentProps<typeof DialogPrimitive.Close>) {
  return <DialogPrimitive.Close data-slot="dialog-close" {...props} />;
}

/** 遮罩：墨色压暗；进入与退出都只有不透明度变化。 */
export function DialogOverlay({ className, ...props }: ComponentProps<typeof DialogPrimitive.Overlay>) {
  return (
    <DialogPrimitive.Overlay
      data-slot="dialog-overlay"
      className={cn(
        'fixed inset-0 z-50 bg-scrim data-[state=closed]:animate-fade-out data-[state=open]:animate-fade-in',
        className,
      )}
      {...props}
    />
  );
}

/** 对话框面板的宽度三档。 */
export const DIALOG_WIDTH = { sm: 'w-100', md: 'w-120', lg: 'w-160' } as const;

/** 对话框和警示对话框共用的面板外观。 */
export const dialogPanel = [
  'shadow-overlay fixed top-1/2 left-1/2 z-50 flex max-h-[calc(100vh-4rem)] max-w-[calc(100vw-2rem)] -translate-x-1/2 -translate-y-1/2 flex-col gap-6',
  'rounded-overlay border border-rule bg-surface-raised p-6 text-ink outline-none',
  'motion-safe:data-[state=open]:animate-dialog-in motion-safe:data-[state=closed]:animate-overlay-out',
  'motion-reduce:data-[state=open]:animate-fade-in motion-reduce:data-[state=closed]:animate-fade-out',
].join(' ');

export interface DialogContentProps extends ComponentProps<typeof DialogPrimitive.Content> {
  size?: keyof typeof DIALOG_WIDTH;
  /** 右上角的关闭按钮，默认显示。 */
  showCloseButton?: boolean;
}

/** 对话框面板：居中，带遮罩；Esc 与关闭按钮都能关闭。 */
export function DialogContent({
  className,
  children,
  size = 'md',
  showCloseButton = true,
  ...props
}: DialogContentProps) {
  return (
    <DialogPortal>
      <DialogOverlay />
      <DialogPrimitive.Content
        data-slot="dialog-content"
        className={cn(dialogPanel, DIALOG_WIDTH[size], className)}
        {...props}
      >
        {children}
        {showCloseButton && (
          <DialogPrimitive.Close asChild>
            <Button variant="ghost" size="icon-sm" aria-label="关闭" className="absolute top-4 right-4">
              <Icon glyph={XIcon} />
            </Button>
          </DialogPrimitive.Close>
        )}
      </DialogPrimitive.Content>
    </DialogPortal>
  );
}

export function DialogHeader({ className, ...props }: ComponentProps<'div'>) {
  return <div data-slot="dialog-header" className={cn('flex flex-col gap-2 pr-8', className)} {...props} />;
}

export interface DialogBodyProps extends ComponentProps<'div'> {
  /** 滚动区域的可访问名称，默认"对话框内容"。 */
  label?: string;
}

/** 内容较长时的滚动区域：可用键盘聚焦后滚动（role="region"），两侧贴到面板边缘。 */
export function DialogBody({ className, label = '对话框内容', ...props }: DialogBodyProps) {
  return (
    <div
      data-slot="dialog-body"
      role="region"
      aria-label={label}
      tabIndex={0}
      className={cn('-mx-6 min-h-0 overflow-y-auto px-6', className)}
      {...props}
    />
  );
}

/** 底部操作：靠右，主操作在最右。 */
export function DialogFooter({ className, ...props }: ComponentProps<'div'>) {
  return <div data-slot="dialog-footer" className={cn('flex justify-end gap-2', className)} {...props} />;
}

export function DialogTitle({ className, ...props }: ComponentProps<typeof DialogPrimitive.Title>) {
  return (
    <DialogPrimitive.Title
      data-slot="dialog-title"
      className={cn('text-16 leading-heading font-semibold', className)}
      {...props}
    />
  );
}

export function DialogDescription({
  className,
  ...props
}: ComponentProps<typeof DialogPrimitive.Description>) {
  return (
    <DialogPrimitive.Description
      data-slot="dialog-description"
      className={cn('text-13 text-ink-2', className)}
      {...props}
    />
  );
}
