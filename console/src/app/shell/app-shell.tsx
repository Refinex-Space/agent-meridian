import { Link } from '@tanstack/react-router';
import { useEffect, useState, type ReactNode } from 'react';

import { StatusGlyph } from '@/components/domain/status-badge';
import { Badge } from '@/components/ui/badge';
import {
  Breadcrumb,
  BreadcrumbItem,
  BreadcrumbLink,
  BreadcrumbList,
  BreadcrumbPage,
  BreadcrumbSeparator,
} from '@/components/ui/breadcrumb';
import { Button } from '@/components/ui/button';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { BrandMark, CaretUpDownIcon, MagnifyingGlassIcon, SidebarSimpleIcon } from '@/components/ui/glyphs';
import { Icon } from '@/components/ui/icon';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import type { TenantProjects } from '@/lib/access';
import { cn } from '@/lib/cn';
import type { ThemePreference } from '@/lib/theme';

import { CommandMenu } from './command-menu';
import { NAV, type NavItem, type NavKey } from './nav';

/** 顶栏面包屑的一项；`mono` 用于 ID。没有 `href` 的一项是当前页。 */
export interface ShellCrumb {
  label: string;
  href?: string;
  mono?: boolean;
}

/** 账号菜单里显示的当前用户。 */
export interface ShellUser {
  name: string;
  email: string | null;
  /** 在当前项目中的角色，例如"项目开发者"；没有角色时为空串。 */
  roles: string;
}

export interface AppShellProps {
  /** 当前一级导航；不在任何一级导航之下（项目不存在等）时为 null。 */
  active: NavKey | null;
  /** 当前项目：导航链接与项目切换都以它为准。 */
  project: { id: string; name: string };
  /** 能进入的租户与项目（项目切换）。 */
  projects: readonly TenantProjects[];
  onSwitchProject: (projectId: string) => void;
  user: ShellUser;
  theme: ThemePreference;
  onThemeChange: (theme: ThemePreference) => void;
  onLogout: () => void;
  /** 待处理动作数，显示在"待处理"上（轮询 `GET /v1/session-stats` 的 `pending_actions`）。 */
  pendingCount?: number;
  breadcrumb: readonly ShellCrumb[];
  /** 顶栏下方的横幅（与服务端断开、预算告警）。 */
  banner?: ReactNode;
  /** 导航的初始折叠状态；不给时读本地记录。 */
  defaultCollapsed?: boolean;
  /** ⌘K 中选择了一个页面。 */
  onNavigate: (item: NavItem) => void;
  children: ReactNode;
}

const COLLAPSED_KEY = 'meridian.nav.collapsed';

function readCollapsed(): boolean {
  try {
    return window.localStorage.getItem(COLLAPSED_KEY) === '1';
  } catch {
    return false;
  }
}

function writeCollapsed(value: boolean): void {
  try {
    window.localStorage.setItem(COLLAPSED_KEY, value ? '1' : '0');
  } catch {
    // 无痕模式或禁用了存储：折叠状态只在本次有效。
  }
}

const THEMES: { value: ThemePreference; label: string }[] = [
  { value: 'light', label: '亮色' },
  { value: 'dark', label: '暗色' },
  { value: 'system', label: '跟随系统' },
];

function ProjectSwitcher({
  collapsed,
  project,
  projects,
  onSwitch,
}: {
  collapsed: boolean;
  project: AppShellProps['project'];
  projects: readonly TenantProjects[];
  onSwitch: (projectId: string) => void;
}) {
  const { name } = project;
  const trigger = collapsed ? (
    <button
      type="button"
      aria-label={`切换项目：${name}`}
      className="flex size-8 items-center justify-center rounded-control border border-rule bg-surface text-13 font-medium hover:border-rule-strong"
    >
      {name.slice(0, 1)}
    </button>
  ) : (
    <button
      type="button"
      aria-label={`切换项目：${name}`}
      className="flex h-9 w-full items-center gap-2 rounded-control border border-rule bg-surface px-3 text-left text-13 hover:border-rule-strong"
    >
      <span className="min-w-0 flex-1 truncate font-medium">{name}</span>
      <Icon glyph={CaretUpDownIcon} className="text-ink-2" />
    </button>
  );
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>{trigger}</DropdownMenuTrigger>
      <DropdownMenuContent side={collapsed ? 'right' : 'bottom'} className="min-w-56">
        <DropdownMenuRadioGroup value={project.id} onValueChange={onSwitch}>
          {projects.map((tenant, i) => (
            <div key={tenant.tenant_id}>
              {i > 0 && <DropdownMenuSeparator />}
              <DropdownMenuLabel>{tenant.tenant_name}</DropdownMenuLabel>
              {tenant.projects.map((p) => (
                <DropdownMenuRadioItem key={p.id} value={p.id}>
                  {p.name}
                </DropdownMenuRadioItem>
              ))}
            </div>
          ))}
        </DropdownMenuRadioGroup>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

function UserMenu({
  user,
  projectName,
  theme,
  onThemeChange,
  onLogout,
}: {
  user: ShellUser;
  projectName: string;
  theme: ThemePreference;
  onThemeChange: (theme: ThemePreference) => void;
  onLogout: () => void;
}) {
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <button
          type="button"
          aria-label={`账号：${user.name}`}
          className="flex size-8 items-center justify-center rounded-control bg-ink text-12 font-semibold text-surface"
        >
          {user.name.slice(0, 1).toUpperCase()}
        </button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="min-w-60">
        <div className="flex flex-col px-2 py-2 text-13">
          <span className="font-medium">{user.name}</span>
          {user.email && <span className="truncate text-12 text-ink-2">{user.email}</span>}
        </div>
        <DropdownMenuSeparator />
        <DropdownMenuLabel>
          {user.roles ? `在${projectName}中是${user.roles}` : `在${projectName}中没有角色`}
        </DropdownMenuLabel>
        <DropdownMenuSeparator />
        <DropdownMenuLabel>主题</DropdownMenuLabel>
        <DropdownMenuRadioGroup value={theme} onValueChange={(v) => onThemeChange(v as ThemePreference)}>
          {THEMES.map((t) => (
            <DropdownMenuRadioItem key={t.value} value={t.value}>
              {t.label}
            </DropdownMenuRadioItem>
          ))}
        </DropdownMenuRadioGroup>
        <DropdownMenuSeparator />
        <DropdownMenuItem onSelect={onLogout}>退出登录</DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

function NavLink({
  item,
  projectId,
  current,
  collapsed,
  pendingCount,
}: {
  item: NavItem;
  projectId: string;
  current: boolean;
  collapsed: boolean;
  pendingCount: number;
}) {
  const pending = item.key === 'pending' && pendingCount > 0;
  const name = pending ? `${item.label}，${pendingCount} 个待处理动作` : item.label;
  const link = (
    <Link
      to={item.to}
      params={{ projectId }}
      aria-current={current ? 'page' : undefined}
      aria-label={collapsed ? name : undefined}
      className={cn(
        'relative flex h-8 items-center rounded-control text-13',
        collapsed ? 'w-10 justify-center' : 'gap-3 px-2',
        current ? 'bg-surface font-medium text-ink' : 'text-ink-2 hover:bg-surface-hover hover:text-ink',
      )}
    >
      <Icon glyph={item.glyph} weight={current ? 'bold' : 'regular'} />
      {!collapsed && <span className="flex-1 truncate">{item.label}</span>}
      {pending &&
        (collapsed ? (
          <span aria-hidden className="absolute top-1 right-1">
            <StatusGlyph kind="requires_action" />
          </span>
        ) : (
          <Badge tone="brass" aria-label={`${pendingCount} 个待处理动作`}>
            {pendingCount}
          </Badge>
        ))}
    </Link>
  );
  if (!collapsed) return link;
  return (
    <Tooltip>
      <TooltipTrigger asChild>{link}</TooltipTrigger>
      <TooltipContent side="right">{name}</TooltipContent>
    </Tooltip>
  );
}

function ShellCrumbItem({ crumb, first }: { crumb: ShellCrumb; first: boolean }) {
  return (
    <>
      {!first && <BreadcrumbSeparator />}
      <BreadcrumbItem className="min-w-0">
        {crumb.href ? (
          <BreadcrumbLink asChild>
            <Link to={crumb.href}>{crumb.label}</Link>
          </BreadcrumbLink>
        ) : (
          <BreadcrumbPage className={cn('truncate', crumb.mono && 'font-mono text-12')}>
            {crumb.label}
          </BreadcrumbPage>
        )}
      </BreadcrumbItem>
    </>
  );
}

/**
 * 应用外壳（frontend-design.md「布局·应用外壳」，T20c 原型的正式版本）：左侧导航 232px（可折叠为 56px 图标栏，状态记在
 * 本地）、项目切换、顶栏 48px（面包屑、⌘K 搜索、账号菜单与主题、退出），顶栏下方是影响整个应用的横幅。只负责呈现，
 * 数据与导航由 `ShellLayout` 接入。
 */
export function AppShell({
  active,
  project,
  projects,
  onSwitchProject,
  user,
  theme,
  onThemeChange,
  onLogout,
  pendingCount = 0,
  breadcrumb,
  banner,
  defaultCollapsed,
  onNavigate,
  children,
}: AppShellProps) {
  const [collapsed, setCollapsed] = useState(() => defaultCollapsed ?? readCollapsed());
  const [commandOpen, setCommandOpen] = useState(false);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key.toLowerCase() === 'k' && (e.metaKey || e.ctrlKey)) {
        e.preventDefault();
        setCommandOpen((open) => !open);
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);

  const toggle = () => {
    setCollapsed((value) => {
      writeCollapsed(!value);
      return !value;
    });
  };

  return (
    <div className="flex h-screen min-h-0 bg-canvas text-ink">
      <nav
        aria-label="主导航"
        className={cn(
          'flex shrink-0 flex-col overflow-hidden border-r border-rule whitespace-nowrap',
          'transition-[width] duration-200 ease-out motion-reduce:transition-none',
          collapsed ? 'w-14' : 'w-58',
        )}
      >
        <div className={cn('flex h-12 shrink-0 items-center gap-2', collapsed ? 'justify-center' : 'px-4')}>
          <Icon glyph={BrandMark} size={24} label={collapsed ? 'AgentMeridian' : undefined} />
          {!collapsed && <span className="text-14 font-semibold">AgentMeridian</span>}
        </div>
        <div className={cn('pt-1 pb-3', collapsed ? 'flex justify-center' : 'px-3')}>
          <ProjectSwitcher
            collapsed={collapsed}
            project={project}
            projects={projects}
            onSwitch={onSwitchProject}
          />
        </div>
        <ul className={cn('flex flex-col gap-1', collapsed ? 'items-center' : 'px-2')}>
          {NAV.map((item) => (
            <li key={item.key}>
              <NavLink
                item={item}
                projectId={project.id}
                current={item.key === active}
                collapsed={collapsed}
                pendingCount={pendingCount}
              />
            </li>
          ))}
        </ul>
        <div className={cn('mt-auto pb-3', collapsed ? 'flex justify-center' : 'px-2')}>
          <Tooltip>
            <TooltipTrigger asChild>
              <Button
                size="icon-md"
                variant="ghost"
                aria-label={collapsed ? '展开导航' : '收起导航'}
                aria-expanded={!collapsed}
                onClick={toggle}
              >
                <Icon glyph={SidebarSimpleIcon} />
              </Button>
            </TooltipTrigger>
            <TooltipContent side="right">{collapsed ? '展开导航' : '收起导航'}</TooltipContent>
          </Tooltip>
        </div>
      </nav>
      <div className="flex min-w-0 flex-1 flex-col">
        <header className="flex h-12 shrink-0 items-center gap-4 border-b border-rule bg-surface px-6">
          <Breadcrumb className="min-w-0 flex-1">
            <BreadcrumbList>
              {breadcrumb.map((crumb, i) => (
                <ShellCrumbItem key={`${crumb.label}-${i}`} crumb={crumb} first={i === 0} />
              ))}
            </BreadcrumbList>
          </Breadcrumb>
          <button
            type="button"
            onClick={() => setCommandOpen(true)}
            className="flex h-8 w-56 items-center gap-2 rounded-control border border-rule bg-canvas px-2 text-13 text-ink-2 hover:border-rule-strong"
          >
            <Icon glyph={MagnifyingGlassIcon} />
            <span className="flex-1 text-left">搜索</span>
            <kbd className="rounded-control border border-rule px-1 font-sans text-12">⌘K</kbd>
          </button>
          <UserMenu
            user={user}
            projectName={project.name}
            theme={theme}
            onThemeChange={onThemeChange}
            onLogout={onLogout}
          />
        </header>
        {banner}
        <main className="min-h-0 flex-1 overflow-auto">{children}</main>
      </div>
      <CommandMenu open={commandOpen} onOpenChange={setCommandOpen} onNavigate={onNavigate} />
    </div>
  );
}
