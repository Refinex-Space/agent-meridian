/*
 * 来源：shadcn/ui new-york-v4 `command`（shadcn CLI 4.23.0 生成，2026-11-13），基于 cmdk。与上游的差异：
 * - 命令面板对话框贴近顶部（距顶 15vh）、宽 560px，打开时不跳到屏幕中央；面板外观与 Dialog 相同（surface-raised、8px 圆角、浮层阴影）；
 * - 搜索行 48px、14px 文字，左侧 Phosphor 的放大镜；结果列表最高 320px；
 * - 结果项 36px 高、13px 文字、4px 圆角，键盘选中为 accent-weak 底（上游 bg-accent）；禁用为 ink-disabled，不用整体半透明；
 * - 分组标题 12px 石墨色、句子式；快捷键去掉 tracking-widest（字距令牌已移除）；空结果 13px 石墨色；
 * - 标题与描述的默认值改为中文（"命令面板""搜索页面、对象与操作"），只给读屏；去掉右上角的关闭按钮（Esc 关闭，面板内没有多余元素）；
 * - 空结果改由 CommandList 的 `empty` 渲染在列表之外，没有结果时列表隐藏：listbox 不能没有选项，也不能含非选项的子元素
 *   （axe 的 aria-required-children）；分隔线不用 cmdk 的 Separator（它固定为 role="separator"，不允许出现在 listbox 中），
 *   改为对读屏隐藏的刻线，搜索时同样隐藏。去掉 CommandEmpty 导出。
 */
import { Command as CommandPrimitive, useCommandState } from 'cmdk';
import type { ComponentProps, ReactNode } from 'react';

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

export interface CommandListProps extends ComponentProps<typeof CommandPrimitive.List> {
  /** 没有匹配结果时显示的一句话（"没有匹配的页面或对象"）。 */
  empty?: ReactNode;
}

/** 结果列表；没有结果时隐藏列表，在原处显示 `empty`。 */
export function CommandList({ className, empty, ...props }: CommandListProps) {
  const count = useCommandState((state) => state.filtered.count);
  return (
    <>
      <CommandPrimitive.List
        data-slot="command-list"
        label="结果"
        hidden={count === 0}
        className={cn('max-h-80 scroll-py-1 overflow-x-hidden overflow-y-auto p-1', className)}
        {...props}
      />
      {empty && (
        <CommandPrimitive.Empty
          data-slot="command-empty"
          className="px-3 py-6 text-center text-13 text-ink-2"
        >
          {empty}
        </CommandPrimitive.Empty>
      )}
    </>
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

/** 结果分组之间的刻线：对读屏隐藏，搜索时不显示。 */
export function CommandSeparator({ className, ...props }: ComponentProps<'div'>) {
  const searching = useCommandState((state) => state.search !== '');
  if (searching) return null;
  return (
    <div
      data-slot="command-separator"
      aria-hidden
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
