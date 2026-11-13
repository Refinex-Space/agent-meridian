import { MutationCache, QueryClient } from '@tanstack/react-query';

import { notifyError } from '@/components/domain/error-state';
import { ApiError, isNetworkError } from '@/lib/api/errors';

declare module '@tanstack/react-query' {
  interface Register {
    mutationMeta: {
      /** 失败时通知的标题，按操作写（"发布失败"）。 */
      errorTitle?: string;
      /**
       * 失败是否发错误通知，默认是。结果就在视野里的操作（表单提交、行内操作）设为 false，由界面就地显示 ErrorState。
       */
      notifyError?: boolean;
    };
  }
}

/** 值得自动重试的失败：网络不可达、限流、服务端错误；4xx 是请求本身的问题，重试也不会成功。 */
export function isRetryable(error: unknown): boolean {
  if (isNetworkError(error)) return true;
  return error instanceof ApiError && (error.status === 429 || error.status >= 500);
}

/**
 * TanStack Query 的统一配置（frontend-design.md「状态与反馈」）：
 * - 查询：可重试的失败最多重试 2 次（指数退避）；30 秒内的数据视为新鲜；页面重新可见时刷新；轮询只在页面可见时进行
 *   （`refetchInterval` 由各查询按 console-api-map.md 的实时性给出）。查询失败由所在区域（DataRegion 或 ErrorState）显示。
 * - 变更：不自动重试（写操作不一定幂等）；失败默认发错误通知（带可复制的请求 ID），`meta.notifyError: false` 时由界面就地显示。
 */
export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        retry: (failures, error) => failures < 2 && isRetryable(error),
        staleTime: 30_000,
        refetchIntervalInBackground: false,
      },
      mutations: { retry: false },
    },
    mutationCache: new MutationCache({
      onError: (error, _variables, _context, mutation) => {
        if (mutation.meta?.notifyError === false) return;
        notifyError(error, mutation.meta?.errorTitle);
      },
    }),
  });
}
