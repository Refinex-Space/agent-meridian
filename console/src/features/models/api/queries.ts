/**
 * 模型页的查询与变更（console-api-map.md「8 模型」）：租户的供应商账号、模型目录、价格表与版本，全部按需刷新；模型测试经公开的
 * 直通接口（ADR-0056）。查询键 `['admin', 租户, 'provider-accounts' | 'models' | 'price-books', …]`；模型目录与智能体编辑页的
 * 选择器同一个键。
 */
import { queryOptions, useMutation, useQueryClient } from '@tanstack/react-query';

import { useProject, useProjectApi } from '@/lib/access';
import { adminApi, unwrap } from '@/lib/api/client';
import { collectPages, PAGE_LIMIT } from '@/lib/api/pages';
import type {
  CatalogModel,
  ChatCompletion,
  CreateModelRequest,
  CreatePriceBookRequest,
  CreateProviderAccountRequest,
  TokenPrices,
  UpdateModelRequest,
  UpdateProviderAccountRequest,
} from '@/lib/api/types';

const page = (after: string | undefined) => ({ limit: PAGE_LIMIT, ...(after ? { after } : {}) });

export const catalogKeys = {
  providers: (t: string) => ['admin', t, 'provider-accounts'] as const,
  models: (t: string) => ['admin', t, 'models'] as const,
  priceBooks: (t: string) => ['admin', t, 'price-books'] as const,
  priceBook: (t: string, id: string) => [...catalogKeys.priceBooks(t), id] as const,
  priceBookVersions: (t: string, id: string) => [...catalogKeys.priceBook(t, id), 'versions'] as const,
};

export const providerAccountsQuery = (tenantId: string) =>
  queryOptions({
    queryKey: catalogKeys.providers(tenantId),
    queryFn: ({ signal }) =>
      collectPages((after) =>
        unwrap(
          adminApi().GET('/admin/v1/tenants/{tenant_id}/provider-accounts', {
            params: { path: { tenant_id: tenantId }, query: page(after) },
            signal,
          }),
        ),
      ),
  });

export const catalogModelsQuery = (tenantId: string) =>
  queryOptions({
    queryKey: catalogKeys.models(tenantId),
    queryFn: ({ signal }) =>
      collectPages((after) =>
        unwrap(
          adminApi().GET('/admin/v1/tenants/{tenant_id}/models', {
            params: { path: { tenant_id: tenantId }, query: page(after) },
            signal,
          }),
        ),
      ),
  });

export const priceBooksQuery = (tenantId: string) =>
  queryOptions({
    queryKey: catalogKeys.priceBooks(tenantId),
    queryFn: ({ signal }) =>
      collectPages((after) =>
        unwrap(
          adminApi().GET('/admin/v1/tenants/{tenant_id}/price-books', {
            params: { path: { tenant_id: tenantId }, query: page(after) },
            signal,
          }),
        ),
      ),
  });

export const priceBookQuery = (tenantId: string, id: string) =>
  queryOptions({
    queryKey: catalogKeys.priceBook(tenantId, id),
    queryFn: ({ signal }) =>
      unwrap(
        adminApi().GET('/admin/v1/tenants/{tenant_id}/price-books/{price_book_id}', {
          params: { path: { tenant_id: tenantId, price_book_id: id } },
          signal,
        }),
      ),
  });

/** 价格表的全部版本（版本号从新到旧）。 */
export const priceBookVersionsQuery = (tenantId: string, id: string) =>
  queryOptions({
    queryKey: catalogKeys.priceBookVersions(tenantId, id),
    queryFn: ({ signal }) =>
      collectPages((after) =>
        unwrap(
          adminApi().GET('/admin/v1/tenants/{tenant_id}/price-books/{price_book_id}/versions', {
            params: { path: { tenant_id: tenantId, price_book_id: id }, query: page(after) },
            signal,
          }),
        ),
      ),
  });

/** 写操作的结果都在对话框或表格行里，失败就地显示。 */
function useCatalogMutation<TVars, TData>(
  mutationFn: (tenantId: string, vars: TVars) => Promise<TData>,
  invalidate: (tenantId: string, vars: TVars) => readonly (readonly unknown[])[],
) {
  const { tenantId } = useProject();
  const queryClient = useQueryClient();
  return useMutation({
    meta: { notifyError: false },
    mutationFn: (vars: TVars) => mutationFn(tenantId, vars),
    onSettled: (_, __, vars) =>
      Promise.all(invalidate(tenantId, vars).map((queryKey) => queryClient.invalidateQueries({ queryKey }))),
  });
}

export function useCreateProviderAccount() {
  return useCatalogMutation(
    (tenantId, body: CreateProviderAccountRequest) =>
      unwrap(
        adminApi().POST('/admin/v1/tenants/{tenant_id}/provider-accounts', {
          params: { path: { tenant_id: tenantId } },
          body,
        }),
      ),
    (t) => [catalogKeys.providers(t)],
  );
}

export function useUpdateProviderAccount() {
  return useCatalogMutation(
    (tenantId, { id, body }: { id: string; body: UpdateProviderAccountRequest }) =>
      unwrap(
        adminApi().PUT('/admin/v1/tenants/{tenant_id}/provider-accounts/{provider_account_id}', {
          params: { path: { tenant_id: tenantId, provider_account_id: id } },
          body,
        }),
      ),
    (t) => [catalogKeys.providers(t)],
  );
}

/** 删除供应商账号；账号下还有模型时 409 `catalog.in_use`。 */
export function useDeleteProviderAccount() {
  return useCatalogMutation(
    (tenantId, id: string) =>
      unwrap(
        adminApi().DELETE('/admin/v1/tenants/{tenant_id}/provider-accounts/{provider_account_id}', {
          params: { path: { tenant_id: tenantId, provider_account_id: id } },
        }),
      ),
    (t) => [catalogKeys.providers(t)],
  );
}

/** 连通性测试：列一次模型清单，不产生用量；结果写进账号的 `last_check`，连不上也不是错误响应。 */
export function useTestProviderAccount() {
  return useCatalogMutation(
    (tenantId, id: string) =>
      unwrap(
        adminApi().POST('/admin/v1/tenants/{tenant_id}/provider-accounts/{provider_account_id}:test', {
          params: { path: { tenant_id: tenantId, provider_account_id: id } },
        }),
      ),
    (t) => [catalogKeys.providers(t)],
  );
}

export function useCreateModel() {
  return useCatalogMutation(
    (tenantId, body: CreateModelRequest) =>
      unwrap(
        adminApi().POST('/admin/v1/tenants/{tenant_id}/models', {
          params: { path: { tenant_id: tenantId } },
          body,
        }),
      ),
    (t) => [catalogKeys.models(t)],
  );
}

export function useUpdateModel() {
  return useCatalogMutation(
    (tenantId, { id, body }: { id: string; body: UpdateModelRequest }) =>
      unwrap(
        adminApi().PUT('/admin/v1/tenants/{tenant_id}/models/{model_id}', {
          params: { path: { tenant_id: tenantId, model_id: id } },
          body,
        }),
      ),
    (t) => [catalogKeys.models(t)],
  );
}

/** 删除模型；被其他模型作为备用模型、或被已发布的智能体版本引用时 409 `catalog.in_use`。 */
export function useDeleteModel() {
  return useCatalogMutation(
    (tenantId, id: string) =>
      unwrap(
        adminApi().DELETE('/admin/v1/tenants/{tenant_id}/models/{model_id}', {
          params: { path: { tenant_id: tenantId, model_id: id } },
        }),
      ),
    (t) => [catalogKeys.models(t)],
  );
}

/** 创建价格表并发布版本 1。 */
export function useCreatePriceBook() {
  return useCatalogMutation(
    (tenantId, body: CreatePriceBookRequest) =>
      unwrap(
        adminApi().POST('/admin/v1/tenants/{tenant_id}/price-books', {
          params: { path: { tenant_id: tenantId } },
          body,
        }),
      ),
    (t) => [catalogKeys.priceBooks(t)],
  );
}

/** 发布价格表的新版本：立即生效，已结算的用量仍按记录中的版本。 */
export function usePublishPriceBookVersion() {
  return useCatalogMutation(
    (tenantId, { id, prices }: { id: string; prices: TokenPrices }) =>
      unwrap(
        adminApi().POST('/admin/v1/tenants/{tenant_id}/price-books/{price_book_id}/versions', {
          params: { path: { tenant_id: tenantId, price_book_id: id } },
          body: { prices },
        }),
      ),
    (t) => [catalogKeys.priceBooks(t)],
  );
}

/** 模型测试的结果：浏览器测得的延迟、回复（对话模型）与响应中的用量。 */
export interface ModelTestResult {
  latencyMs: number;
  reply: string | null;
  inputTokens: number;
  outputTokens: number;
}

/** 测试请求的内容：一句话、至多 16 个输出 token。 */
export const TEST_PROMPT = '只回复：OK';
export const TEST_MAX_TOKENS = 16;

/**
 * 测试一个模型：经公开的直通接口发一次最小请求（对话模型 `/v1/chat/completions`，Embedding 模型 `/v1/embeddings`），与业务调用
 * 一样经过路由、限流、预算与计量，费用计入当前项目（ADR-0056）。
 */
export function useTestModel() {
  const api = useProjectApi();
  return useMutation({
    meta: { notifyError: false },
    mutationFn: async (model: Pick<CatalogModel, 'id' | 'kind'>): Promise<ModelTestResult> => {
      const started = performance.now();
      if (model.kind === 'embedding') {
        const result = await unwrap(
          api.POST('/v1/embeddings', {
            body: { model: model.id, input: TEST_PROMPT, encoding_format: 'float' },
          }),
        );
        return {
          latencyMs: Math.round(performance.now() - started),
          reply: null,
          inputTokens: result.usage.prompt_tokens,
          outputTokens: 0,
        };
      }
      const result = await unwrap(
        api.POST('/v1/chat/completions', {
          body: {
            model: model.id,
            messages: [{ role: 'user', content: TEST_PROMPT }],
            max_tokens: TEST_MAX_TOKENS,
            stream: false,
          },
        }),
      );
      // 非流式请求得到 ChatCompletion；生成的类型把流式响应的块也并进了联合类型。
      if (Array.isArray(result)) throw new Error('模型直通接口返回了流式响应');
      const completion: ChatCompletion = result;
      const content = completion.choices[0]?.message.content;
      return {
        latencyMs: Math.round(performance.now() - started),
        reply: typeof content === 'string' ? content : null,
        inputTokens: completion.usage?.prompt_tokens ?? 0,
        outputTokens: completion.usage?.completion_tokens ?? 0,
      };
    },
  });
}
