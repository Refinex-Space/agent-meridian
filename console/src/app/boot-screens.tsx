import { Link } from '@tanstack/react-router';
import type { ReactNode } from 'react';

import { EmptyState } from '@/components/domain/empty-state';
import { ErrorState } from '@/components/domain/error-state';
import { Button } from '@/components/ui/button';
import { BrandMark } from '@/components/ui/glyphs';
import { Icon } from '@/components/ui/icon';

/** 外壳之外的整页状态（登录失败、没有项目、页面不存在）：顶部只有品牌，内容居中。 */
export function FullPage({ children }: { children: ReactNode }) {
  return (
    <div className="flex min-h-screen flex-col bg-canvas text-ink">
      <header className="flex h-12 shrink-0 items-center gap-2 border-b border-rule bg-surface px-4">
        <Icon glyph={BrandMark} size={24} />
        <span className="text-14 font-semibold">AgentMeridian</span>
      </header>
      <main className="flex flex-1 items-center justify-center">{children}</main>
    </div>
  );
}

export interface BootFailureProps {
  /** 发生了什么（"无法登录"）。 */
  title: string;
  error: unknown;
  actionLabel: string;
  onAction: () => void;
}

/** 启动失败：读取配置、登录或读取身份失败，写明原因并给出重试或重新登录。 */
export function BootFailure({ title, error, actionLabel, onAction }: BootFailureProps) {
  return (
    <FullPage>
      <div className="flex max-w-160 flex-col items-center pb-12">
        <ErrorState error={error} title={title} />
        <Button variant="primary" onClick={onAction}>
          {actionLabel}
        </Button>
      </div>
    </FullPage>
  );
}

/** 已登录但还不是任何项目的成员。 */
export function NoProjects({ onLogout }: { onLogout: () => void }) {
  return (
    <FullPage>
      <EmptyState
        variant="first-use"
        title="你还没有加入任何项目，请联系租户管理员把你加为成员"
        action={<Button onClick={onLogout}>换一个账号登录</Button>}
      />
    </FullPage>
  );
}

/** 地址不对应任何页面，或项目不存在、没有权限进入（两者同样处理，不泄露项目是否存在）。 */
export function NotFound({ title = '这个页面不存在' }: { title?: string }) {
  return (
    <EmptyState
      variant="filtered"
      title={title}
      action={
        <Button asChild>
          <Link to="/">回到首页</Link>
        </Button>
      }
    />
  );
}
