import { loadConfig } from '@/lib/auth/config';
import { completeSilentSignin, createUserManager, SILENT_CALLBACK_PATH } from '@/lib/auth/session';

/**
 * 入口。静默登录在隐藏的 iframe 中加载本页面的 `/auth/silent-callback`：只把授权响应交给父页面，不加载样式与应用；
 * 其余情况启动控制台（`bootstrap`，按需加载）。
 */
async function main(): Promise<void> {
  if (window.location.pathname === SILENT_CALLBACK_PATH) {
    await completeSilentSignin(createUserManager(await loadConfig()));
    return;
  }
  const { bootstrap } = await import('./bootstrap');
  await bootstrap();
}

void main();
