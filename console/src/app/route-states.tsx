import { useRouter, type ErrorComponentProps } from '@tanstack/react-router';

import { useAuth } from '@/lib/auth/auth-context';

import { BootFailure, NoProjects } from './boot-screens';

/** 首页：已登录但不是任何项目的成员。 */
export function NoProjectsPage() {
  const { logout } = useAuth();
  return <NoProjects onLogout={() => void logout()} />;
}

/** 读取身份或项目失败（服务不可用等）：整页说明，重试时重新执行路由的加载。 */
function RouteFailure({ error, reset }: ErrorComponentProps) {
  const router = useRouter();
  return (
    <BootFailure
      title="无法读取你的项目"
      error={error}
      actionLabel="重试"
      onAction={() => {
        reset();
        void router.invalidate();
      }}
    />
  );
}

export const HomeRedirectFailed = RouteFailure;
export const ProjectErrorPage = RouteFailure;
