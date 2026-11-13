import { ErrorResponse, User, type UserManager } from 'oidc-client-ts';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { AuthSession, CALLBACK_PATH, startAuth, type AuthManager } from './session';

function user(
  token: string,
  expiresInSeconds: number,
  profile: Record<string, unknown> = {},
  state?: unknown,
) {
  return new User({
    access_token: token,
    token_type: 'Bearer',
    expires_at: Math.floor(Date.now() / 1000) + expiresInSeconds,
    profile: { sub: 'sub-1', iss: 'http://idp', aud: 'meridian-console', exp: 0, iat: 0, ...profile },
    userState: state,
  });
}

/** UserManager 的替身：只实现 AuthSession 与 startAuth 用到的方法。 */
function fakeManager() {
  let onLoaded: ((u: User) => void) | undefined;
  const manager = {
    signinSilent: vi.fn<() => Promise<User | null>>(),
    signinRedirect: vi.fn<(args?: { state?: unknown }) => Promise<void>>().mockResolvedValue(undefined),
    signoutRedirect: vi.fn<() => Promise<void>>().mockResolvedValue(undefined),
    removeUser: vi.fn<() => Promise<void>>().mockResolvedValue(undefined),
    signinRedirectCallback: vi.fn<() => Promise<User>>(),
    clearStaleState: vi.fn<() => Promise<void>>().mockResolvedValue(undefined),
    events: {
      addUserLoaded: (cb: (u: User) => void) => {
        onLoaded = cb;
      },
    },
    load: (u: User) => onLoaded?.(u),
  };
  return manager;
}

/** startAuth 只用到 UserManager 的一小部分；替身按它用到的方法实现。 */
const asUserManager = (m: ReturnType<typeof fakeManager>) => m as unknown as UserManager;
const asAuthManager = (m: ReturnType<typeof fakeManager>): AuthManager => m;

describe('AuthSession', () => {
  it('令牌还有效时直接返回，不续期', async () => {
    const manager = fakeManager();
    const session = new AuthSession(asAuthManager(manager), user('tok_1', 600));
    expect(await session.getAccessToken()).toBe('tok_1');
    expect(manager.signinSilent).not.toHaveBeenCalled();
  });

  it('快过期时先续期；并发的请求共用同一次续期', async () => {
    const manager = fakeManager();
    manager.signinSilent.mockResolvedValue(user('tok_2', 900));
    const session = new AuthSession(asAuthManager(manager), user('tok_1', 10));
    const tokens = await Promise.all([session.getAccessToken(), session.getAccessToken()]);
    expect(tokens).toEqual(['tok_2', 'tok_2']);
    expect(manager.signinSilent).toHaveBeenCalledTimes(1);
    expect(await session.getAccessToken()).toBe('tok_2');
  });

  it('续期失败时返回 null，请求会得到 401', async () => {
    const manager = fakeManager();
    manager.signinSilent.mockRejectedValue(new Error('login_required'));
    const session = new AuthSession(asAuthManager(manager), user('tok_1', 5));
    expect(await session.getAccessToken()).toBeNull();
  });

  describe('401', () => {
    beforeEach(() => {
      window.history.replaceState(null, '', '/projects/prj_1/agents?status=idle');
    });
    afterEach(() => {
      window.history.replaceState(null, '', '/');
    });

    it('先续期一次；续期失败就跳转登录，登录后回到当前页面', async () => {
      const manager = fakeManager();
      manager.signinSilent.mockRejectedValue(new Error('login_required'));
      const session = new AuthSession(asAuthManager(manager), user('tok_1', 600));
      session.handleUnauthorized();
      await vi.waitFor(() => expect(manager.signinRedirect).toHaveBeenCalledTimes(1));
      expect(manager.removeUser).toHaveBeenCalled();
      expect(manager.signinRedirect).toHaveBeenCalledWith({ state: '/projects/prj_1/agents?status=idle' });
      session.handleUnauthorized();
      expect(manager.signinRedirect).toHaveBeenCalledTimes(1);
    });

    it('刚续期过仍然 401（令牌对服务端无效）时不再续期，直接重新登录', async () => {
      const manager = fakeManager();
      manager.signinSilent.mockResolvedValue(user('tok_2', 900));
      const session = new AuthSession(asAuthManager(manager), user('tok_1', 600));
      session.handleUnauthorized();
      await vi.waitFor(() => expect(manager.signinSilent).toHaveBeenCalledTimes(1));
      await Promise.resolve();
      session.handleUnauthorized();
      await vi.waitFor(() => expect(manager.signinRedirect).toHaveBeenCalledTimes(1));
      expect(manager.signinSilent).toHaveBeenCalledTimes(1);
    });
  });

  it('显示名依次取 name、preferred_username、邮箱、用户标识；后台续期更新声明并通知', () => {
    const manager = fakeManager();
    const session = new AuthSession(
      asAuthManager(manager),
      user('t', 600, { preferred_username: 'dev-developer', email: 'd@x.local' }),
    );
    expect(session.user).toEqual({ subject: 'sub-1', name: 'dev-developer', email: 'd@x.local' });
    const listener = vi.fn();
    session.subscribe(listener);
    const before = session.user;
    manager.load(user('t2', 600, { preferred_username: 'dev-developer', email: 'd@x.local' }));
    expect(listener).not.toHaveBeenCalled();
    expect(session.user).toBe(before);
    manager.load(user('t3', 600, { name: 'Dev Developer', email: 'd@x.local' }));
    expect(listener).toHaveBeenCalledTimes(1);
    expect(session.user.name).toBe('Dev Developer');
  });

  it('退出登录经身份提供方结束会话', async () => {
    const manager = fakeManager();
    await new AuthSession(asAuthManager(manager), user('t', 600)).logout();
    expect(manager.signoutRedirect).toHaveBeenCalled();
  });
});

describe('startAuth', () => {
  afterEach(() => {
    window.history.replaceState(null, '', '/');
  });

  it('授权码回调：换取令牌后回到登录前的页面', async () => {
    window.history.replaceState(null, '', `${CALLBACK_PATH}?code=c&state=s`);
    const manager = fakeManager();
    manager.signinRedirectCallback.mockResolvedValue(user('tok', 900, {}, '/projects/prj_1/sessions'));
    const result = await startAuth(asUserManager(manager));
    expect(result.kind).toBe('signed-in');
    expect(window.location.pathname).toBe('/projects/prj_1/sessions');
  });

  it('授权码回调失败（例如用户拒绝授权）：说明原因并可以重新登录', async () => {
    window.history.replaceState(null, '', `${CALLBACK_PATH}?error=access_denied&state=s`);
    const manager = fakeManager();
    manager.signinRedirectCallback.mockRejectedValue(new ErrorResponse({ error: 'access_denied' }));
    const result = await startAuth(asUserManager(manager));
    expect(result.kind).toBe('failed');
    if (result.kind === 'failed') await result.retry();
    expect(manager.signinRedirect).toHaveBeenCalled();
  });

  it('刷新页面：身份提供方有会话时静默登录', async () => {
    window.history.replaceState(null, '', '/projects/prj_1/overview');
    const manager = fakeManager();
    manager.signinSilent.mockResolvedValue(user('tok', 900));
    const result = await startAuth(asUserManager(manager));
    expect(result.kind).toBe('signed-in');
    expect(manager.signinRedirect).not.toHaveBeenCalled();
  });

  it('身份提供方没有会话（login_required）时跳转登录，记下当前页面', async () => {
    window.history.replaceState(null, '', '/projects/prj_1/overview');
    const manager = fakeManager();
    manager.signinSilent.mockRejectedValue(new ErrorResponse({ error: 'login_required' }));
    expect((await startAuth(asUserManager(manager))).kind).toBe('redirecting');
    expect(manager.signinRedirect).toHaveBeenCalledWith({ state: '/projects/prj_1/overview' });
  });

  it('身份提供方不可达（超时、网络错误）时不跳转，说明原因', async () => {
    const manager = fakeManager();
    manager.signinSilent.mockRejectedValue(new Error('Frame window timed out'));
    expect((await startAuth(asUserManager(manager))).kind).toBe('failed');
    expect(manager.signinRedirect).not.toHaveBeenCalled();
  });
});
