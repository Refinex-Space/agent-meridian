import { CommandDialog, CommandGroup, CommandInput, CommandItem, CommandList } from '@/components/ui/command';
import { Icon } from '@/components/ui/icon';

import { NAV, type NavItem } from './nav';

export interface CommandMenuProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** 选中一个页面：跳转后关闭。 */
  onNavigate: (item: NavItem) => void;
}

/**
 * ⌘K 命令面板：按名称跳到当前项目的任一页面。智能体与最近的会话由有对应详情页的任务（T22、T24）加入。
 */
export function CommandMenu({ open, onOpenChange, onNavigate }: CommandMenuProps) {
  return (
    <CommandDialog open={open} onOpenChange={onOpenChange}>
      <CommandInput placeholder="搜索页面" />
      <CommandList empty="没有匹配的页面">
        <CommandGroup heading="页面">
          {NAV.map((item) => (
            <CommandItem
              key={item.key}
              value={`页面 ${item.label}`}
              onSelect={() => {
                onOpenChange(false);
                onNavigate(item);
              }}
            >
              <Icon glyph={item.glyph} />
              {item.label}
            </CommandItem>
          ))}
        </CommandGroup>
      </CommandList>
    </CommandDialog>
  );
}
