import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';

import { TooltipProvider } from '@/components/ui/tooltip';
import { AppShell } from '@/prototypes/shell/app-shell';
import { SessionDetail } from '@/prototypes/session-detail/session-detail';
import '@/styles/index.css';

import { MODEL_NAMES, Q3_SESSION_ID } from '@mocks/fixtures/q3-session';

/**
 * 应用入口。T20 阶段还没有路由与认证，入口直接展示会话详情原型，接口由 MSW 模拟。
 * TODO(T21)：换成路由树、认证与真实的 API 客户端，移除 MSW 与原型。
 */
async function start() {
  const { worker } = await import('@mocks/browser');
  await worker.start({ quiet: true });
  const root = document.getElementById('root');
  if (!root) throw new Error('index.html 缺少 #root');
  createRoot(root).render(
    <StrictMode>
      <TooltipProvider>
        <AppShell
          active="会话"
          pendingCount={2}
          breadcrumb={<span className="font-mono text-12 text-ink">{Q3_SESSION_ID}</span>}
        >
          <SessionDetail sessionId={Q3_SESSION_ID} modelNames={MODEL_NAMES} />
        </AppShell>
      </TooltipProvider>
    </StrictMode>,
  );
}

void start();
