import { StrictMode } from 'react';
import { createRoot, type Root } from 'react-dom/client';

import { setAuthHandlers } from '@/lib/api/client';
import { loadConfig } from '@/lib/auth/config';
import { createUserManager, startAuth } from '@/lib/auth/session';
import { initTheme } from '@/lib/theme';
import '@/styles/index.css';

import { App } from './app';
import { BootFailure } from './boot-screens';
import { createQueryClient } from './query-client';
import { createAppRouter } from './router';

function mount(): Root {
  const element = document.getElementById('root');
  if (!element) throw new Error('index.html 缺少 #root');
  return createRoot(element);
}

/**
 * 启动控制台：读运行时配置 → 登录（授权码回调、静默登录或跳转身份提供方）→ 登记令牌来源 → 渲染路由。登录之前不渲染外壳；
 * 读取配置或登录失败时整页说明原因，提供重试或重新登录。
 */
export async function bootstrap(): Promise<void> {
  initTheme();
  const root = mount();
  let config;
  try {
    config = await loadConfig();
  } catch (error) {
    root.render(
      <BootFailure
        title="无法读取控制台的配置"
        error={error}
        actionLabel="重试"
        onAction={() => window.location.reload()}
      />,
    );
    return;
  }
  const result = await startAuth(createUserManager(config));
  if (result.kind === 'redirecting') return;
  if (result.kind === 'failed') {
    root.render(
      <BootFailure
        title="无法登录"
        error={result.error}
        actionLabel="重新登录"
        onAction={() => void result.retry()}
      />,
    );
    return;
  }
  const { session } = result;
  setAuthHandlers({
    getAccessToken: () => session.getAccessToken(),
    onUnauthorized: () => session.handleUnauthorized(),
  });
  const queryClient = createQueryClient();
  const router = createAppRouter(queryClient);
  root.render(
    <StrictMode>
      <App session={session} queryClient={queryClient} router={router} />
    </StrictMode>,
  );
}
