/**
 * 凭据的查询与写入（console-api-map.md「11 设置·凭据」，ADR-0045、ADR-0056）。凭据属于租户：列表含租户级与各项目的凭据，
 * 只有元数据与引用方。查询键 `['admin', 租户, 'secrets']`。
 *
 * 明文只写不读：写入与轮换不经 `useMutation`（变更缓存会把请求的 `variables` 留到垃圾回收），由调用方的局部状态记录进度与错误；
 * 明文只出现在这一次请求体中。
 */
import { queryOptions, useMutation, useQueryClient } from '@tanstack/react-query';
import { useCallback, useState } from 'react';

import { useProject } from '@/lib/access';
import { adminApi, unwrap } from '@/lib/api/client';
import { collectPages, PAGE_LIMIT } from '@/lib/api/pages';
import type { Secret } from '@/lib/api/types';

export const secretKeys = {
  all: (tenantId: string) => ['admin', tenantId, 'secrets'] as const,
};

/** 租户的全部凭据（按创建时间）；需要 `secret_read`。 */
export const secretsQuery = (tenantId: string) =>
  queryOptions({
    queryKey: secretKeys.all(tenantId),
    queryFn: ({ signal }) =>
      collectPages((after) =>
        unwrap(
          adminApi().GET('/admin/v1/tenants/{tenant_id}/secrets', {
            params: {
              path: { tenant_id: tenantId },
              query: { limit: PAGE_LIMIT, ...(after ? { after } : {}) },
            },
            signal,
          }),
        ),
      ),
  });

/** 写入凭据的请求：`project_id` 为 null 是租户级凭据。 */
export interface SecretWrite {
  name: string;
  projectId: string | null;
  value: string;
}

export interface SecretWriter<T> {
  /** 发出请求；成功后刷新凭据列表。失败时抛出，错误同时记在 `error`。 */
  run: (input: T) => Promise<Secret>;
  pending: boolean;
  error: unknown;
  reset: () => void;
}

/** 不进查询缓存的写操作：只在组件状态里记"提交中"与错误。 */
function useSecretWriter<T>(send: (tenantId: string, input: T) => Promise<Secret>): SecretWriter<T> {
  const { tenantId } = useProject();
  const queryClient = useQueryClient();
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const run = useCallback(
    async (input: T) => {
      setPending(true);
      setError(null);
      try {
        const secret = await send(tenantId, input);
        await queryClient.invalidateQueries({ queryKey: secretKeys.all(tenantId) });
        return secret;
      } catch (e) {
        setError(e);
        throw e;
      } finally {
        setPending(false);
      }
    },
    [send, tenantId, queryClient],
  );
  const reset = useCallback(() => setError(null), []);
  return { run, pending, error, reset };
}

const create = (tenantId: string, input: SecretWrite) =>
  unwrap(
    adminApi().POST('/admin/v1/tenants/{tenant_id}/secrets', {
      params: { path: { tenant_id: tenantId } },
      body: { name: input.name, project_id: input.projectId, value: input.value },
    }),
  );

const rotate = (tenantId: string, input: { id: string; value: string }) =>
  unwrap(
    adminApi().POST('/admin/v1/tenants/{tenant_id}/secrets/{secret_id}:rotate', {
      params: { path: { tenant_id: tenantId, secret_id: input.id } },
      body: { value: input.value },
    }),
  );

/** 写入凭据。 */
export function useCreateSecret(): SecretWriter<SecretWrite> {
  return useSecretWriter(create);
}

/** 轮换凭据：写入新值，版本号加一，旧值不可恢复。 */
export function useRotateSecret(): SecretWriter<{ id: string; value: string }> {
  return useSecretWriter(rotate);
}

/** 删除凭据（不含明文，照常用变更）；仍被引用时 409 `secret.in_use`。 */
export function useDeleteSecret() {
  const { tenantId } = useProject();
  const queryClient = useQueryClient();
  return useMutation({
    meta: { notifyError: false },
    mutationFn: (id: string) =>
      unwrap(
        adminApi().DELETE('/admin/v1/tenants/{tenant_id}/secrets/{secret_id}', {
          params: { path: { tenant_id: tenantId, secret_id: id } },
        }),
      ),
    onSettled: () => queryClient.invalidateQueries({ queryKey: secretKeys.all(tenantId) }),
  });
}
