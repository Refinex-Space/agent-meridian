import {
  CommandDialog,
  CommandGroup,
  CommandInput,
  CommandItem,
  CommandList,
  CommandSeparator,
} from '@/components/ui/command';
import { AgentGlyph, ThreadGlyph } from '@/components/ui/glyphs';
import { Icon } from '@/components/ui/icon';
import { middleEllipsis } from '@/lib/format';

import { PROJECT_AGENTS } from '@mocks/fixtures/agents';
import { Q3_SESSION_ID } from '@mocks/fixtures/q3-session';

import { NAV } from './nav';

/** 最近打开的会话（正式版本记在本地）。 */
const RECENT_SESSIONS = [
  { id: Q3_SESSION_ID, agent: '经营分析助手' },
  { id: 'ses_01JCQ7H2K8N4R0T6W2Y8B4D0FG', agent: '客服质检' },
  { id: 'ses_01JCPZ3B9D5F1H7K3M9P5R1TVX', agent: '门店巡检报告' },
];

export interface CommandMenuProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

/**
 * ⌘K 命令面板：跳到页面、智能体与最近的会话。输入会话 ID 时直接打开（T21 接路由后跳转）。
 */
export function CommandMenu({ open, onOpenChange }: CommandMenuProps) {
  const close = () => onOpenChange(false);
  return (
    <CommandDialog open={open} onOpenChange={onOpenChange}>
      <CommandInput placeholder="搜索页面、智能体或会话 ID" />
      <CommandList empty="没有匹配的页面、智能体或会话">
        <CommandGroup heading="页面">
          {NAV.map((item) => (
            <CommandItem key={item.key} value={`页面 ${item.label}`} onSelect={close}>
              <Icon glyph={item.glyph} />
              {item.label}
            </CommandItem>
          ))}
        </CommandGroup>
        <CommandSeparator />
        <CommandGroup heading="智能体">
          {PROJECT_AGENTS.map((agent) => (
            <CommandItem key={agent.id} value={`智能体 ${agent.name} ${agent.id}`} onSelect={close}>
              <Icon glyph={AgentGlyph} />
              {agent.name}
            </CommandItem>
          ))}
        </CommandGroup>
        <CommandSeparator />
        <CommandGroup heading="最近的会话">
          {RECENT_SESSIONS.map((session) => (
            <CommandItem key={session.id} value={`会话 ${session.id} ${session.agent}`} onSelect={close}>
              <Icon glyph={ThreadGlyph} />
              <span className="font-mono text-12">{middleEllipsis(session.id)}</span>
              <span className="ml-auto text-12 text-ink-2">{session.agent}</span>
            </CommandItem>
          ))}
        </CommandGroup>
      </CommandList>
    </CommandDialog>
  );
}
