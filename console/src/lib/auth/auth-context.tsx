import { createContext, use, useSyncExternalStore, type ReactNode } from 'react';

import type { AuthSession, AuthUser } from './session';

const AuthContext = createContext<AuthSession | null>(null);

export interface AuthProviderProps {
  session: AuthSession;
  children: ReactNode;
}

/** 已登录的会话，放在应用根部；未登录时应用不会渲染（启动时已跳转登录）。 */
export function AuthProvider({ session, children }: AuthProviderProps) {
  return <AuthContext value={session}>{children}</AuthContext>;
}

export interface Auth {
  user: AuthUser;
  logout: () => Promise<void>;
}

/** 当前用户与退出登录。 */
export function useAuth(): Auth {
  const session = use(AuthContext);
  if (!session) throw new Error('useAuth 需要在 AuthProvider 之内使用');
  const user = useSyncExternalStore(
    (listener) => session.subscribe(listener),
    () => session.user,
  );
  return { user, logout: () => session.logout() };
}
