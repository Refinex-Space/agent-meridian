import {
  CommandDialog,
  CommandGroup,
  CommandInput,
  CommandItem,
  CommandList,
  CommandShortcut,
} from '@/components/ui/command';
import { Icon, type Glyph } from '@/components/ui/icon';

import { NAV, type NavItem } from './nav';

/** 命令面板中的一组对象（智能体、最近的会话），选中后跳到它的详情。 */
export interface CommandObjectGroup {
  heading: string;
  items: readonly {
    key: string;
    label: string;
    /** 右侧的补充信息（当前版本）。 */
    detail?: string;
    glyph: Glyph;
    onSelect: () => void;
  }[];
}

export interface CommandMenuProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** 选中一个页面：跳转后关闭。 */
  onNavigate: (item: NavItem) => void;
  /** 页面之外的对象分组（T22 的智能体；最近的会话由 T24 加入）。 */
  groups?: readonly CommandObjectGroup[];
}

/**
 * ⌘K 命令面板：按名称跳到当前项目的任一页面或对象（智能体）。
 */
export function CommandMenu({ open, onOpenChange, onNavigate, groups = [] }: CommandMenuProps) {
  return (
    <CommandDialog open={open} onOpenChange={onOpenChange}>
      <CommandInput placeholder={groups.length > 0 ? '搜索页面或智能体' : '搜索页面'} />
      <CommandList empty={groups.length > 0 ? '没有匹配的页面或智能体' : '没有匹配的页面'}>
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
        {groups
          .filter((group) => group.items.length > 0)
          .map((group) => (
            <CommandGroup key={group.heading} heading={group.heading}>
              {group.items.map((item) => (
                <CommandItem
                  key={item.key}
                  value={`${group.heading} ${item.label} ${item.key}`}
                  onSelect={() => {
                    onOpenChange(false);
                    item.onSelect();
                  }}
                >
                  <Icon glyph={item.glyph} />
                  <span className="min-w-0 flex-1 truncate">{item.label}</span>
                  {item.detail && <CommandShortcut>{item.detail}</CommandShortcut>}
                </CommandItem>
              ))}
            </CommandGroup>
          ))}
      </CommandList>
    </CommandDialog>
  );
}
