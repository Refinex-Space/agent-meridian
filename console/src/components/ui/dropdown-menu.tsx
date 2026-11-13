/*
 * 来源：shadcn/ui new-york-v4 `dropdown-menu`（shadcn CLI 4.23.0 生成，2026-11-13）。与上游的差异：
 * - 浮层：surface-raised 底、1px 刻线、8px 圆角、唯一的浮层阴影；出现 150ms 不透明度加 2% 缩放，退出 ease-in，
 *   减少动效时只有不透明度；动画不依赖 tw-animate-css；
 * - 菜单项 32px 高、13px 文字、4px 圆角，高亮为 surface-hover 底；禁用为 ink-disabled，不用整体半透明；
 *   图标默认石墨色（调用方给了颜色类时不覆盖），由调用方经 Icon 传入；
 * - `variant="destructive"` 改名 `danger`：信号红文字、高亮时 danger-weak 底；
 * - 勾选与单选项的记号：勾选用 Phosphor 的 Check（海图蓝），单选用 6px 实心圆（控件自身的形状）；子菜单记号换成 CaretRight；
 * - 快捷键去掉 tracking-widest（字距令牌已移除），12px 石墨色；分组标题 12px 石墨色，句子式；
 * - 模态菜单打开时给浮层以外的内容加 inert（InertOutside），与 Radix 加的 aria-hidden 一致；
 * - 去掉 "use client"。
 */
import { DropdownMenu as DropdownMenuPrimitive } from 'radix-ui';
import type { ComponentProps } from 'react';

import { cn } from '@/lib/cn';

import { CaretRightIcon, CheckIcon } from './glyphs';
import { Icon } from './icon';
import { InertOutside } from './inert-outside';

const menuPanel = [
  'shadow-overlay z-50 min-w-40 overflow-x-hidden overflow-y-auto rounded-overlay border border-rule bg-surface-raised p-1 text-ink',
  'origin-(--radix-dropdown-menu-content-transform-origin)',
  'motion-safe:data-[state=open]:animate-overlay-in motion-safe:data-[state=closed]:animate-overlay-out',
  'motion-reduce:data-[state=open]:animate-fade-in motion-reduce:data-[state=closed]:animate-fade-out',
].join(' ');

const menuItem = [
  'relative flex h-8 cursor-default items-center gap-2 rounded-control px-2 text-13 outline-hidden select-none',
  'data-highlighted:bg-surface-hover data-disabled:pointer-events-none data-disabled:text-ink-disabled',
  "[&_svg:not([class*='text-'])]:text-ink-2 data-disabled:[&_svg]:text-ink-disabled",
].join(' ');

/** 下拉菜单：一组针对当前对象的操作（行操作、"更多"）。 */
export function DropdownMenu(props: ComponentProps<typeof DropdownMenuPrimitive.Root>) {
  return <DropdownMenuPrimitive.Root data-slot="dropdown-menu" {...props} />;
}

export function DropdownMenuPortal(props: ComponentProps<typeof DropdownMenuPrimitive.Portal>) {
  return <DropdownMenuPrimitive.Portal data-slot="dropdown-menu-portal" {...props} />;
}

export function DropdownMenuTrigger(props: ComponentProps<typeof DropdownMenuPrimitive.Trigger>) {
  return <DropdownMenuPrimitive.Trigger data-slot="dropdown-menu-trigger" {...props} />;
}

export function DropdownMenuContent({
  className,
  sideOffset = 4,
  align = 'start',
  children,
  ...props
}: ComponentProps<typeof DropdownMenuPrimitive.Content>) {
  return (
    <DropdownMenuPrimitive.Portal>
      <DropdownMenuPrimitive.Content
        data-slot="dropdown-menu-content"
        sideOffset={sideOffset}
        align={align}
        className={cn(menuPanel, 'max-h-(--radix-dropdown-menu-content-available-height)', className)}
        {...props}
      >
        <InertOutside />
        {children}
      </DropdownMenuPrimitive.Content>
    </DropdownMenuPrimitive.Portal>
  );
}

export function DropdownMenuGroup(props: ComponentProps<typeof DropdownMenuPrimitive.Group>) {
  return <DropdownMenuPrimitive.Group data-slot="dropdown-menu-group" {...props} />;
}

export interface DropdownMenuItemProps extends ComponentProps<typeof DropdownMenuPrimitive.Item> {
  /** 与勾选项对齐时留出左侧记号的位置。 */
  inset?: boolean;
  /** 删除类操作：信号红文字。点击后仍要经 DangerConfirmDialog 确认。 */
  variant?: 'default' | 'danger';
}

export function DropdownMenuItem({ className, inset, variant = 'default', ...props }: DropdownMenuItemProps) {
  return (
    <DropdownMenuPrimitive.Item
      data-slot="dropdown-menu-item"
      data-inset={inset}
      data-variant={variant}
      className={cn(
        menuItem,
        'data-inset:pl-8',
        'data-[variant=danger]:text-danger data-[variant=danger]:data-highlighted:bg-danger-weak data-[variant=danger]:[&_svg]:text-danger',
        className,
      )}
      {...props}
    />
  );
}

export function DropdownMenuCheckboxItem({
  className,
  children,
  ...props
}: ComponentProps<typeof DropdownMenuPrimitive.CheckboxItem>) {
  return (
    <DropdownMenuPrimitive.CheckboxItem
      data-slot="dropdown-menu-checkbox-item"
      className={cn(menuItem, 'pl-8', className)}
      {...props}
    >
      <span className="pointer-events-none absolute left-2 flex size-4 items-center justify-center">
        <DropdownMenuPrimitive.ItemIndicator className="flex">
          <Icon glyph={CheckIcon} className="text-accent" />
        </DropdownMenuPrimitive.ItemIndicator>
      </span>
      {children}
    </DropdownMenuPrimitive.CheckboxItem>
  );
}

export function DropdownMenuRadioGroup(props: ComponentProps<typeof DropdownMenuPrimitive.RadioGroup>) {
  return <DropdownMenuPrimitive.RadioGroup data-slot="dropdown-menu-radio-group" {...props} />;
}

export function DropdownMenuRadioItem({
  className,
  children,
  ...props
}: ComponentProps<typeof DropdownMenuPrimitive.RadioItem>) {
  return (
    <DropdownMenuPrimitive.RadioItem
      data-slot="dropdown-menu-radio-item"
      className={cn(menuItem, 'pl-8', className)}
      {...props}
    >
      <span className="pointer-events-none absolute left-2 flex size-4 items-center justify-center">
        <DropdownMenuPrimitive.ItemIndicator className="size-1.5 rounded-full bg-accent" />
      </span>
      {children}
    </DropdownMenuPrimitive.RadioItem>
  );
}

export function DropdownMenuLabel({
  className,
  inset,
  ...props
}: ComponentProps<typeof DropdownMenuPrimitive.Label> & { inset?: boolean }) {
  return (
    <DropdownMenuPrimitive.Label
      data-slot="dropdown-menu-label"
      data-inset={inset}
      className={cn('px-2 pt-2 pb-1 text-12 font-medium text-ink-2 data-inset:pl-8', className)}
      {...props}
    />
  );
}

export function DropdownMenuSeparator({
  className,
  ...props
}: ComponentProps<typeof DropdownMenuPrimitive.Separator>) {
  return (
    <DropdownMenuPrimitive.Separator
      data-slot="dropdown-menu-separator"
      className={cn('-mx-1 my-1 h-px bg-rule', className)}
      {...props}
    />
  );
}

/** 菜单项右侧的快捷键提示。 */
export function DropdownMenuShortcut({ className, ...props }: ComponentProps<'span'>) {
  return (
    <span
      data-slot="dropdown-menu-shortcut"
      className={cn('ml-auto pl-4 text-12 text-ink-2', className)}
      {...props}
    />
  );
}

export function DropdownMenuSub(props: ComponentProps<typeof DropdownMenuPrimitive.Sub>) {
  return <DropdownMenuPrimitive.Sub data-slot="dropdown-menu-sub" {...props} />;
}

export function DropdownMenuSubTrigger({
  className,
  inset,
  children,
  ...props
}: ComponentProps<typeof DropdownMenuPrimitive.SubTrigger> & { inset?: boolean }) {
  return (
    <DropdownMenuPrimitive.SubTrigger
      data-slot="dropdown-menu-sub-trigger"
      data-inset={inset}
      className={cn(menuItem, 'data-inset:pl-8 data-[state=open]:bg-surface-hover', className)}
      {...props}
    >
      {children}
      <Icon glyph={CaretRightIcon} className="ml-auto" />
    </DropdownMenuPrimitive.SubTrigger>
  );
}

export function DropdownMenuSubContent({
  className,
  ...props
}: ComponentProps<typeof DropdownMenuPrimitive.SubContent>) {
  return (
    <DropdownMenuPrimitive.Portal>
      <DropdownMenuPrimitive.SubContent
        data-slot="dropdown-menu-sub-content"
        className={cn(menuPanel, className)}
        {...props}
      />
    </DropdownMenuPrimitive.Portal>
  );
}
