import { QueryClientProvider, type QueryClient } from '@tanstack/react-query';
import { RouterProvider } from '@tanstack/react-router';

import { Toaster } from '@/components/ui/sonner';
import { TooltipProvider } from '@/components/ui/tooltip';
import { AuthProvider } from '@/lib/auth/auth-context';
import type { AuthSession } from '@/lib/auth/session';

import type { createAppRouter } from './router';

export interface AppProps {
  session: AuthSession;
  queryClient: QueryClient;
  router: ReturnType<typeof createAppRouter>;
}

/** 全局 Provider：登录会话、服务端状态（TanStack Query）、提示、路由与通知。 */
export function App({ session, queryClient, router }: AppProps) {
  return (
    <AuthProvider session={session}>
      <QueryClientProvider client={queryClient}>
        <TooltipProvider>
          <RouterProvider router={router} />
          <Toaster />
        </TooltipProvider>
      </QueryClientProvider>
    </AuthProvider>
  );
}
