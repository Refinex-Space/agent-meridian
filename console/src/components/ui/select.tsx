/*
 * 来源：shadcn/ui new-york-v4 `select`（shadcn CLI 4.23.0 生成，2026-11-13）。与上游的差异：
 * - 触发器外观与输入框一致（field-styles），尺寸 sm 28px、md 32px；占位文字为石墨色；去掉 shadow-xs、dark: 分支与 ring 光晕；
 * - 浮层：surface-raised 底、1px 刻线、8px 圆角、唯一的浮层阴影；出现 150ms 不透明度加 2% 缩放，退出 ease-in，
 *   减少动效时只有不透明度；动画不依赖 tw-animate-css，关键帧在 tokens.css；
 * - 默认 `position="popper"`（上游 item-aligned），列表在触发器正下方展开、与触发器同宽，位置可预期；
 * - 选项 32px 高、13px 文字，高亮为 surface-hover 底，选中项右侧是海图蓝对勾；禁用项为 ink-disabled，不用整体半透明；
 * - 图标换成 Phosphor 的封装（CaretDown、CaretUp、Check）。
 */
import { Select as SelectPrimitive } from 'radix-ui';
import type { ComponentProps } from 'react';

import { cn } from '@/lib/cn';

import { controlHeight, controlSurface, type ControlSize } from './field-styles';
import { CaretDownIcon, CaretUpIcon, CheckIcon } from './glyphs';
import { Icon } from './icon';

/** 选择器：选项超过 5 个、或空间不够放单选组时使用。 */
export function Select(props: ComponentProps<typeof SelectPrimitive.Root>) {
  return <SelectPrimitive.Root data-slot="select" {...props} />;
}

export function SelectGroup(props: ComponentProps<typeof SelectPrimitive.Group>) {
  return <SelectPrimitive.Group data-slot="select-group" {...props} />;
}

export function SelectValue(props: ComponentProps<typeof SelectPrimitive.Value>) {
  return <SelectPrimitive.Value data-slot="select-value" {...props} />;
}

export interface SelectTriggerProps extends ComponentProps<typeof SelectPrimitive.Trigger> {
  size?: ControlSize;
}

/** 触发器：与输入框同一外观，右侧是下拉记号。 */
export function SelectTrigger({ className, size = 'md', children, ...props }: SelectTriggerProps) {
  return (
    <SelectPrimitive.Trigger
      data-slot="select-trigger"
      className={cn(
        controlSurface,
        controlHeight[size],
        'flex items-center justify-between gap-2 text-left whitespace-nowrap data-placeholder:text-ink-2',
        '*:data-[slot=select-value]:truncate',
        className,
      )}
      {...props}
    >
      {children}
      <SelectPrimitive.Icon className="flex text-ink-2">
        <Icon glyph={CaretDownIcon} />
      </SelectPrimitive.Icon>
    </SelectPrimitive.Trigger>
  );
}

/** 选项列表浮层。 */
export function SelectContent({
  className,
  children,
  position = 'popper',
  sideOffset = 4,
  ...props
}: ComponentProps<typeof SelectPrimitive.Content>) {
  return (
    <SelectPrimitive.Portal>
      <SelectPrimitive.Content
        data-slot="select-content"
        position={position}
        sideOffset={position === 'popper' ? sideOffset : undefined}
        className={cn(
          'shadow-overlay relative z-50 max-h-(--radix-select-content-available-height) min-w-32 origin-(--radix-select-content-transform-origin) overflow-x-hidden overflow-y-auto rounded-overlay border border-rule bg-surface-raised text-ink',
          'motion-safe:data-[state=closed]:animate-overlay-out motion-safe:data-[state=open]:animate-overlay-in',
          'motion-reduce:data-[state=closed]:animate-fade-out motion-reduce:data-[state=open]:animate-fade-in',
          className,
        )}
        {...props}
      >
        <SelectScrollUpButton />
        <SelectPrimitive.Viewport
          className={cn(
            'p-1',
            position === 'popper' && 'w-full min-w-(--radix-select-trigger-width) scroll-my-1',
          )}
        >
          {children}
        </SelectPrimitive.Viewport>
        <SelectScrollDownButton />
      </SelectPrimitive.Content>
    </SelectPrimitive.Portal>
  );
}

/** 选项分组的标题：12px 石墨色，句子式。 */
export function SelectLabel({ className, ...props }: ComponentProps<typeof SelectPrimitive.Label>) {
  return (
    <SelectPrimitive.Label
      data-slot="select-label"
      className={cn('px-2 pt-2 pb-1 text-12 font-medium text-ink-2', className)}
      {...props}
    />
  );
}

/** 单个选项：选中时右侧显示对勾。 */
export function SelectItem({ className, children, ...props }: ComponentProps<typeof SelectPrimitive.Item>) {
  return (
    <SelectPrimitive.Item
      data-slot="select-item"
      className={cn(
        'relative flex h-8 w-full cursor-default items-center gap-2 rounded-control pr-8 pl-2 text-13 outline-hidden select-none',
        'data-disabled:pointer-events-none data-disabled:text-ink-disabled data-highlighted:bg-surface-hover',
        className,
      )}
      {...props}
    >
      <SelectPrimitive.ItemText>{children}</SelectPrimitive.ItemText>
      <span data-slot="select-item-indicator" className="absolute right-2 flex items-center">
        <SelectPrimitive.ItemIndicator>
          <Icon glyph={CheckIcon} className="text-accent" />
        </SelectPrimitive.ItemIndicator>
      </span>
    </SelectPrimitive.Item>
  );
}

export function SelectSeparator({ className, ...props }: ComponentProps<typeof SelectPrimitive.Separator>) {
  return (
    <SelectPrimitive.Separator
      data-slot="select-separator"
      className={cn('pointer-events-none -mx-1 my-1 h-px bg-rule', className)}
      {...props}
    />
  );
}

function SelectScrollUpButton({
  className,
  ...props
}: ComponentProps<typeof SelectPrimitive.ScrollUpButton>) {
  return (
    <SelectPrimitive.ScrollUpButton
      data-slot="select-scroll-up-button"
      className={cn('flex cursor-default items-center justify-center py-1 text-ink-2', className)}
      {...props}
    >
      <Icon glyph={CaretUpIcon} />
    </SelectPrimitive.ScrollUpButton>
  );
}

function SelectScrollDownButton({
  className,
  ...props
}: ComponentProps<typeof SelectPrimitive.ScrollDownButton>) {
  return (
    <SelectPrimitive.ScrollDownButton
      data-slot="select-scroll-down-button"
      className={cn('flex cursor-default items-center justify-center py-1 text-ink-2', className)}
      {...props}
    >
      <Icon glyph={CaretDownIcon} />
    </SelectPrimitive.ScrollDownButton>
  );
}
