/*
 * 来源：shadcn/ui new-york-v4 `alert-dialog`（shadcn CLI 4.23.0 生成，2026-11-13）。与上游的差异：
 * - 遮罩、面板外观与动效和 Dialog 相同（共用 dialog.tsx 的 dialogPanel 与 DIALOG_WIDTH）；
 * - 头部、底部左对齐（上游 sm 尺寸居中、带 media 槽位的网格布局都去掉）；去掉 AlertDialogMedia（不放装饰图标）；
 * - Action、Cancel 用本项目 Button 的变体：确认默认 primary，危险操作由调用方传 `variant="danger"`，取消为 secondary；
 * - 去掉 "use client"。
 * 不可逆操作的确认统一用领域组件 DangerConfirmDialog，它在这里之上写明后果、要求输入名称。
 */
import { AlertDialog as AlertDialogPrimitive } from 'radix-ui';
import type { ComponentProps } from 'react';

import { cn } from '@/lib/cn';

import { Button, type ButtonProps } from './button';
import { DIALOG_WIDTH, dialogPanel } from './dialog';

/** 警示对话框：必须明确选择确认或取消，点遮罩不会关闭。 */
export function AlertDialog(props: ComponentProps<typeof AlertDialogPrimitive.Root>) {
  return <AlertDialogPrimitive.Root data-slot="alert-dialog" {...props} />;
}

export function AlertDialogTrigger(props: ComponentProps<typeof AlertDialogPrimitive.Trigger>) {
  return <AlertDialogPrimitive.Trigger data-slot="alert-dialog-trigger" {...props} />;
}

export function AlertDialogPortal(props: ComponentProps<typeof AlertDialogPrimitive.Portal>) {
  return <AlertDialogPrimitive.Portal data-slot="alert-dialog-portal" {...props} />;
}

export function AlertDialogOverlay({
  className,
  ...props
}: ComponentProps<typeof AlertDialogPrimitive.Overlay>) {
  return (
    <AlertDialogPrimitive.Overlay
      data-slot="alert-dialog-overlay"
      className={cn(
        'fixed inset-0 z-50 bg-scrim data-[state=closed]:animate-fade-out data-[state=open]:animate-fade-in',
        className,
      )}
      {...props}
    />
  );
}

export interface AlertDialogContentProps extends ComponentProps<typeof AlertDialogPrimitive.Content> {
  size?: keyof typeof DIALOG_WIDTH;
}

export function AlertDialogContent({ className, size = 'md', ...props }: AlertDialogContentProps) {
  return (
    <AlertDialogPortal>
      <AlertDialogOverlay />
      <AlertDialogPrimitive.Content
        data-slot="alert-dialog-content"
        className={cn(dialogPanel, DIALOG_WIDTH[size], className)}
        {...props}
      />
    </AlertDialogPortal>
  );
}

export function AlertDialogHeader({ className, ...props }: ComponentProps<'div'>) {
  return <div data-slot="alert-dialog-header" className={cn('flex flex-col gap-2', className)} {...props} />;
}

export function AlertDialogFooter({ className, ...props }: ComponentProps<'div'>) {
  return (
    <div data-slot="alert-dialog-footer" className={cn('flex justify-end gap-2', className)} {...props} />
  );
}

export function AlertDialogTitle({ className, ...props }: ComponentProps<typeof AlertDialogPrimitive.Title>) {
  return (
    <AlertDialogPrimitive.Title
      data-slot="alert-dialog-title"
      className={cn('text-16 leading-heading font-semibold', className)}
      {...props}
    />
  );
}

export function AlertDialogDescription({
  className,
  ...props
}: ComponentProps<typeof AlertDialogPrimitive.Description>) {
  return (
    <AlertDialogPrimitive.Description
      data-slot="alert-dialog-description"
      className={cn('text-14 leading-body text-ink', className)}
      {...props}
    />
  );
}

type ActionProps = ComponentProps<typeof AlertDialogPrimitive.Action> & Pick<ButtonProps, 'variant' | 'size'>;

/**
 * 确认按钮：文字写清动作（"删除智能体"），不写"确定"。点击后立即关闭；需要等待请求结果的确认用普通 Button，
 * 成功后再关闭（DangerConfirmDialog 就是这样做的）。
 */
export function AlertDialogAction({ className, variant = 'primary', size = 'md', ...props }: ActionProps) {
  return (
    <Button variant={variant} size={size} asChild>
      <AlertDialogPrimitive.Action data-slot="alert-dialog-action" className={className} {...props} />
    </Button>
  );
}

type CancelProps = ComponentProps<typeof AlertDialogPrimitive.Cancel> & Pick<ButtonProps, 'variant' | 'size'>;

export function AlertDialogCancel({ className, variant = 'secondary', size = 'md', ...props }: CancelProps) {
  return (
    <Button variant={variant} size={size} asChild>
      <AlertDialogPrimitive.Cancel data-slot="alert-dialog-cancel" className={className} {...props} />
    </Button>
  );
}
