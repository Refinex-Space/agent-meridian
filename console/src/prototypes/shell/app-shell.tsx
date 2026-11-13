import type { MouseEvent, ReactNode } from 'react';

import { Badge } from '@/components/ui/badge';
import {
  AgentGlyph,
  BrandMark,
  CaretUpDownIcon,
  CoinsIcon,
  CpuIcon,
  FlaskIcon,
  GaugeIcon,
  GearSixIcon,
  KnowledgeGlyph,
  MagnifyingGlassIcon,
  PendingActionGlyph,
  SandboxGlyph,
  SidebarSimpleIcon,
  SkillGlyph,
  ThreadGlyph,
} from '@/components/ui/glyphs';
import { Icon, type Glyph } from '@/components/ui/icon';
import { cn } from '@/lib/cn';

/** 一级导航（架构设计「控制台·信息架构」）。 */
const NAV: { label: string; glyph: Glyph; href: string }[] = [
  { label: '概览', glyph: GaugeIcon, href: '/overview' },
  { label: '会话', glyph: ThreadGlyph, href: '/sessions' },
  { label: 'Playground', glyph: FlaskIcon, href: '/playground' },
  { label: '智能体', glyph: AgentGlyph, href: '/agents' },
  { label: '环境', glyph: SandboxGlyph, href: '/environments' },
  { label: '能力', glyph: SkillGlyph, href: '/capabilities' },
  { label: '知识库', glyph: KnowledgeGlyph, href: '/knowledge' },
  { label: '模型', glyph: CpuIcon, href: '/models' },
  { label: '待处理', glyph: PendingActionGlyph, href: '/pending' },
  { label: '用量与预算', glyph: CoinsIcon, href: '/usage' },
  { label: '设置', glyph: GearSixIcon, href: '/settings' },
];

export interface AppShellProps {
  /** 当前一级导航。 */
  active: string;
  /** 待处理动作数，显示在"待处理"上。 */
  pendingCount?: number;
  breadcrumb: ReactNode;
  children: ReactNode;
}

/** 原型中的链接不跳转（路由在 T21 建立）。 */
const stay = (e: MouseEvent) => e.preventDefault();

/**
 * 应用外壳的原型：左侧导航 232px、顶栏 48px（frontend-design.md「布局·应用外壳」）。T21 用路由与认证实现正式版本。
 */
export function AppShell({ active, pendingCount = 0, breadcrumb, children }: AppShellProps) {
  return (
    <div className="flex h-screen min-h-0 bg-canvas text-ink">
      <nav aria-label="主导航" className="flex w-58 shrink-0 flex-col border-r border-rule">
        <div className="flex h-12 items-center gap-2 px-4">
          <Icon glyph={BrandMark} size={24} />
          <span className="text-14 font-semibold">AgentMeridian</span>
        </div>
        <div className="px-3 pt-1 pb-3">
          <button
            type="button"
            className="flex h-9 w-full items-center gap-2 rounded-control border border-rule bg-surface px-3 text-left text-13 hover:border-rule-strong"
          >
            <span className="flex min-w-0 flex-1 flex-col leading-4">
              <span className="truncate font-medium">华东经营分析</span>
            </span>
            <Icon glyph={CaretUpDownIcon} className="text-ink-2" />
          </button>
        </div>
        <ul className="flex flex-col gap-1 px-2">
          {NAV.map((item) => {
            const current = item.label === active;
            return (
              <li key={item.label}>
                <a
                  href={item.href}
                  onClick={stay}
                  aria-current={current ? 'page' : undefined}
                  className={cn(
                    'flex h-8 items-center gap-3 rounded-control px-2 text-13',
                    current
                      ? 'bg-surface font-medium text-ink'
                      : 'text-ink-2 hover:bg-surface-hover hover:text-ink',
                  )}
                >
                  <Icon glyph={item.glyph} weight={current ? 'bold' : 'regular'} />
                  <span className="flex-1">{item.label}</span>
                  {item.label === '待处理' && pendingCount > 0 && (
                    <Badge tone="brass" aria-label={`${pendingCount} 个待处理动作`}>
                      {pendingCount}
                    </Badge>
                  )}
                </a>
              </li>
            );
          })}
        </ul>
        <div className="mt-auto px-2 pb-3">
          <button
            type="button"
            className="flex h-8 w-full items-center gap-3 rounded-control px-2 text-13 text-ink-2 hover:bg-surface-hover hover:text-ink"
          >
            <Icon glyph={SidebarSimpleIcon} />
            收起导航
          </button>
        </div>
      </nav>
      <div className="flex min-w-0 flex-1 flex-col">
        <header className="flex h-12 shrink-0 items-center gap-4 border-b border-rule bg-surface px-6">
          <div className="flex min-w-0 flex-1 items-center gap-2 text-13">{breadcrumb}</div>
          <button
            type="button"
            className="flex h-8 w-56 items-center gap-2 rounded-control border border-rule bg-canvas px-2 text-13 text-ink-2 hover:border-rule-strong"
          >
            <Icon glyph={MagnifyingGlassIcon} />
            <span className="flex-1 text-left">搜索</span>
            <kbd className="rounded-control border border-rule px-1 font-sans text-12">⌘K</kbd>
          </button>
          <button
            type="button"
            aria-label="账号：华东大区运营"
            className="flex size-8 items-center justify-center rounded-control bg-ink text-12 font-semibold text-surface"
          >
            运
          </button>
        </header>
        <main className="min-h-0 flex-1">{children}</main>
      </div>
    </div>
  );
}
