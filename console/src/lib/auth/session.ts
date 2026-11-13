/**
 * 控制台用户的登录（架构设计「安全架构·认证」）：OIDC 授权码加 PKCE，用 oidc-client-ts。
 *
 * - 令牌只在内存：`userStore` 是 InMemoryWebStorage，刷新页面后令牌即丢失；交互状态（PKCE 的 code_verifier）放在
 *   sessionStorage，回调完成后删除。
 * - 静默续期：access token 到期前由 refresh token 在后台续期（`automaticSilentRenew`）；刷新页面后用隐藏的 iframe 以
 *   `prompt=none` 向身份提供方取新令牌（依赖它的登录会话），失败（`login_required`）时跳转登录。
 * - 退出：`end_session_endpoint`，回到控制台后因为没有会话而跳转登录。
 */
import {
  ErrorResponse,
  InMemoryWebStorage,
  UserManager,
  WebStorageStateStore,
  type User,
} from 'oidc-client-ts';

import type { ConsoleConfig } from './config';

/** 授权码回调（整页跳转回来）。 */
export const CALLBACK_PATH = '/auth/callback';
/** 静默登录回调（隐藏的 iframe 中加载）。 */
export const SILENT_CALLBACK_PATH = '/auth/silent-callback';

/** access token 剩余有效期少于它时，先续期再发请求。 */
const RENEW_BEFORE_SECONDS = 30;
/** 续期成功后这么短的时间内仍然 401：令牌对服务端无效（例如用户被禁用），重新登录而不是再续期。 */
const RENEW_GRACE_MS = 10_000;

/** 当前用户（OIDC 令牌中的声明，界面显示用；角色与权限另取 `GET /admin/v1/me`）。 */
export interface AuthUser {
  subject: string;
  /** 显示名：`name`，没有时依次取 `preferred_username`、邮箱、用户标识。 */
  name: string;
  email: string | null;
}

/** AuthSession 用到的 UserManager 子集（测试用替身实现）。 */
export interface AuthManager {
  signinSilent(): Promise<User | null>;
  signinRedirect(args?: { state?: unknown }): Promise<void>;
  signoutRedirect(): Promise<void>;
  removeUser(): Promise<void>;
  events: { addUserLoaded(cb: (user: User) => void): unknown };
}

export function createUserManager(config: ConsoleConfig, origin = window.location.origin): UserManager {
  return new UserManager({
    authority: config.oidc.authority,
    client_id: config.oidc.clientId,
    scope: config.oidc.scope ?? 'openid profile email',
    redirect_uri: `${origin}${CALLBACK_PATH}`,
    silent_redirect_uri: `${origin}${SILENT_CALLBACK_PATH}`,
    post_logout_redirect_uri: `${origin}/`,
    userStore: new WebStorageStateStore({ store: new InMemoryWebStorage() }),
    stateStore: new WebStorageStateStore({ store: window.sessionStorage }),
    automaticSilentRenew: true,
    silentRequestTimeoutInSeconds: 10,
    // 身份由 control-plane 的 `GET /admin/v1/me` 给出，不必再取 userinfo。
    loadUserInfo: false,
    monitorSession: false,
  });
}

function toAuthUser(user: User): AuthUser {
  const { profile } = user;
  const email = typeof profile.email === 'string' ? profile.email : null;
  const name = profile.name ?? profile.preferred_username ?? email ?? profile.sub;
  return { subject: profile.sub, name, email };
}

/** 当前页面的路径（登录后回到这里）。 */
function currentPath(): string {
  const { pathname, search, hash } = window.location;
  return pathname === CALLBACK_PATH ? '/' : `${pathname}${search}${hash}`;
}

/** 已登录的会话：提供 access token、续期、重新登录与退出。 */
export class AuthSession {
  readonly #manager: AuthManager;
  #user: User;
  #authUser: AuthUser;
  #renewing: Promise<User | null> | null = null;
  #renewedAt = 0;
  #redirecting = false;
  readonly #listeners = new Set<() => void>();

  constructor(manager: AuthManager, user: User) {
    this.#manager = manager;
    this.#user = user;
    this.#authUser = toAuthUser(user);
    manager.events.addUserLoaded((next) => this.#setUser(next));
  }

  #setUser(user: User): void {
    this.#user = user;
    const next = toAuthUser(user);
    const current = this.#authUser;
    if (next.subject === current.subject && next.name === current.name && next.email === current.email)
      return;
    this.#authUser = next;
    for (const listener of this.#listeners) listener();
  }

  /** 当前用户；同一用户的声明不变时返回同一个对象（供 useSyncExternalStore 读取）。 */
  get user(): AuthUser {
    return this.#authUser;
  }

  /** 用户信息变化（续期后声明可能更新）时通知。 */
  subscribe(listener: () => void): () => void {
    this.#listeners.add(listener);
    return () => this.#listeners.delete(listener);
  }

  /** 当前有效的 access token；快过期时先续期。续期失败返回 null（请求会得到 401，再由 `handleUnauthorized` 处理）。 */
  async getAccessToken(): Promise<string | null> {
    const remaining = this.#user.expires_in;
    if (remaining !== undefined && remaining > RENEW_BEFORE_SECONDS) return this.#user.access_token;
    try {
      const user = await this.renew();
      return user?.access_token ?? null;
    } catch {
      return null;
    }
  }

  /** 续期（refresh token，没有时用 iframe）；并发的调用共用同一次续期。 */
  renew(): Promise<User | null> {
    this.#renewing ??= this.#manager
      .signinSilent()
      .then((user) => {
        if (user) {
          this.#setUser(user);
          this.#renewedAt = Date.now();
        }
        return user;
      })
      .finally(() => {
        this.#renewing = null;
      });
    return this.#renewing;
  }

  /** 服务端返回 401：先续期一次；续期失败，或刚续期过仍然 401，就重新登录。 */
  handleUnauthorized(): void {
    if (this.#redirecting) return;
    if (Date.now() - this.#renewedAt < RENEW_GRACE_MS) {
      void this.signInAgain();
      return;
    }
    this.renew().catch(() => this.signInAgain());
  }

  /** 跳转到身份提供方重新登录，登录后回到当前页面。 */
  async signInAgain(): Promise<void> {
    if (this.#redirecting) return;
    this.#redirecting = true;
    await this.#manager.removeUser();
    await this.#manager.signinRedirect({ state: currentPath() });
  }

  /** 退出登录：结束身份提供方的会话，回到控制台后跳转登录。 */
  async logout(): Promise<void> {
    this.#redirecting = true;
    await this.#manager.signoutRedirect();
  }
}

/** 启动时登录的结果：已登录、正在跳转到身份提供方，或失败（由启动页显示原因并提供重试）。 */
export type AuthStart =
  | { kind: 'signed-in'; session: AuthSession }
  | { kind: 'redirecting' }
  | { kind: 'failed'; error: unknown; retry: () => Promise<void> };

/** 身份提供方说明需要用户交互：没有登录会话、需要授权或重新认证。 */
function needsInteraction(error: unknown): boolean {
  return (
    error instanceof ErrorResponse &&
    ['login_required', 'interaction_required', 'consent_required', 'account_selection_required'].includes(
      error.error ?? '',
    )
  );
}

/**
 * 启动时取得登录状态：处理授权码回调；否则先静默登录（刷新页面的情形），身份提供方没有会话时跳转登录。
 */
export async function startAuth(manager: UserManager): Promise<AuthStart> {
  const redirect = async () => {
    await manager.signinRedirect({ state: currentPath() });
  };
  await manager.clearStaleState();
  if (window.location.pathname === CALLBACK_PATH) {
    try {
      const user = await manager.signinRedirectCallback();
      const returnTo = typeof user.state === 'string' && user.state.startsWith('/') ? user.state : '/';
      window.history.replaceState(null, '', returnTo);
      return { kind: 'signed-in', session: new AuthSession(manager, user) };
    } catch (error) {
      return { kind: 'failed', error, retry: redirect };
    }
  }
  try {
    const user = await manager.signinSilent();
    if (user) return { kind: 'signed-in', session: new AuthSession(manager, user) };
  } catch (error) {
    if (!needsInteraction(error)) return { kind: 'failed', error, retry: redirect };
  }
  await redirect();
  return { kind: 'redirecting' };
}

/** 隐藏 iframe 中的静默登录回调：把授权响应交给父页面。 */
export async function completeSilentSignin(manager: UserManager): Promise<void> {
  await manager.signinSilentCallback();
}
