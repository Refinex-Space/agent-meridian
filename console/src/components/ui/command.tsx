/*
 * 来源：shadcn/ui new-york-v4 `command`（shadcn CLI 4.23.0 生成，2026-11-13），基于 cmdk。与上游的差异：
 * - 命令面板对话框贴近顶部（距顶 15vh）、宽 560px，打开时不跳到屏幕中央；面板外观与 Dialog 相同（surface-raised、8px 圆角、浮层阴影）；
 * - 搜索行 48px、14px 文字，左侧 Phosphor 的放大镜；结果列表最高 320px；
 * - 结果项 36px 高、13px 文字、4px 圆角，键盘选中为 accent-weak 底（上游 bg-accent）；禁用为 ink-disabled，不用整体半透明；
 * - 分组标题 12px 石墨色、句子式；快捷键去掉 tracking-widest（字距令牌已移除）；空结果 13px 石墨色；
 * - 标题与描述的默认值改为中文（"命令面板""搜索页面、对象与操作"），只给读屏；去掉右上角的关闭按钮（Esc 关闭，面板内没有多余元素）。
 */
import { Command as CommandPrimitive } from 'cmdk';
import type { ComponentProps } from 'react';

import { cn } from '@/lib/cn';

import { Dialog, DialogContent, DialogDescription, DialogTitle } from './dialog';
import { MagnifyingGlassIcon } from './glyphs';
import { Icon } from './icon';

/** 命令列表的容器：可以单独嵌在页面里，也可以放进 CommandDialog。 */
export function Command({ className, ...props }: ComponentProps<typeof CommandPrimitive>) {
  return (
    <CommandPrimitive
      data-slot="command"
      className={cn('flex size-full flex-col overflow-hidden bg-surface-raised text-ink', className)}
      {...props}
    />
  );
}

export interface CommandDialogProps extends ComponentProps<typeof Dialog> {
  title?: string;
  description?: string;
  className?: string;
  /** 传给 cmdk 的过滤设置（例如 `shouldFilter={false}` 由调用方按服务端结果过滤）。 */
  commandProps?: ComponentProps<typeof CommandPrimitive>;
}

/** ⌘K 命令面板：全局搜索与跳转。 */
export function CommandDialog({
  title = '命令面板',
  description = '搜索页面、对象与操作',
  children,
  className,
  commandProps,
  ...props
}: CommandDialogProps) {
  return (
    <Dialog {...props}>
      <DialogContent
        showCloseButton={false}
        className={cn('top-[15vh] w-140 translate-y-0 gap-0 overflow-hidden p-0', className)}
      >
        <DialogTitle className="sr-only">{title}</DialogTitle>
        <DialogDescription className="sr-only">{description}</DialogDescription>
        <Command {...commandProps}>{children}</Command>
      </DialogContent>
    </Dialog>
  );
}

/** 搜索行：48px，下方一条刻线。 */
export function CommandInput({ className, ...props }: ComponentProps<typeof CommandPrimitive.Input>) {
  return (
    <div data-slot="command-input-wrapper" className="flex h-12 items-center gap-2 border-b border-rule px-4">
      <Icon glyph={MagnifyingGlassIcon} className="text-ink-2" />
      <CommandPrimitive.Input
        data-slot="command-input"
        className={cn(
          'h-full w-full bg-transparent text-14 text-ink outline-hidden placeholder:text-ink-2 disabled:cursor-not-allowed disabled:text-ink-disabled',
          className,
        )}
        {...props}
      />
    </div>
  );
}

export function CommandList({ className, ...props }: ComponentProps<typeof CommandPrimitive.List>) {
  return (
    <CommandPrimitive.List
      data-slot="command-list"
      className={cn('max-h-80 scroll-py-1 overflow-x-hidden overflow-y-auto p-1', className)}
      {...props}
    />
  );
}

export function CommandEmpty({ className, ...props }: ComponentProps<typeof CommandPrimitive.Empty>) {
  return (
    <CommandPrimitive.Empty
      data-slot="command-empty"
      className={cn('px-3 py-6 text-center text-13 text-ink-2', className)}
      {...props}
    />
  );
}

/** 一组结果：标题 12px 石墨色。 */
export function CommandGroup({ className, ...props }: ComponentProps<typeof CommandPrimitive.Group>) {
  return (
    <CommandPrimitive.Group
      data-slot="command-group"
      className={cn(
        'overflow-hidden text-ink **:[[cmdk-group-heading]]:px-2 **:[[cmdk-group-heading]]:pt-2 **:[[cmdk-group-heading]]:pb-1 **:[[cmdk-group-heading]]:text-12 **:[[cmdk-group-heading]]:font-medium **:[[cmdk-group-heading]]:text-ink-2',
        className,
      )}
      {...props}
    />
  );
}

export function CommandSeparator({ className, ...props }: ComponentProps<typeof CommandPrimitive.Separator>) {
  return (
    <CommandPrimitive.Separator
      data-slot="command-separator"
      className={cn('-mx-1 my-1 h-px bg-rule', className)}
      {...props}
    />
  );
}

/** 单个结果：键盘选中为海图蓝浅底，图标石墨色。 */
export function CommandItem({ className, ...props }: ComponentProps<typeof CommandPrimitive.Item>) {
  return (
    <CommandPrimitive.Item
      data-slot="command-item"
      className={cn(
        'relative flex h-9 cursor-default items-center gap-3 rounded-control px-2 text-13 outline-hidden select-none',
        'data-[disabled=true]:pointer-events-none data-[disabled=true]:text-ink-disabled data-[selected=true]:bg-accent-weak',
        "[&_svg:not([class*='text-'])]:text-ink-2",
        className,
      )}
      {...props}
    />
  );
}

/** 结果右侧的快捷键或类型提示。 */
export function CommandShortcut({ className, ...props }: ComponentProps<'span'>) {
  return (
    <span data-slot="command-shortcut" className={cn('ml-auto text-12 text-ink-2', className)} {...props} />
  );
}
