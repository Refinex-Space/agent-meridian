import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import createClient from 'openapi-fetch';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { TooltipProvider } from '@/components/ui/tooltip';
import { ProjectProvider, type CurrentProject } from '@/lib/access';
import type { paths as AdminPaths } from '@/lib/api/admin';
import type * as ClientModule from '@/lib/api/client';

import { CreateSecretDialog, RotateSecretDialog } from './secret-dialogs';

const fetchMock = vi.fn<typeof fetch>();

// 管理 API 的客户端换成指向假 fetch 的同一类型客户端；unwrap 与错误处理照常。
vi.mock('@/lib/api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof ClientModule>()),
  adminApi: () => createClient<AdminPaths>({ baseUrl: 'http://console.test', fetch: (r) => fetchMock(r) }),
}));

const PLAINTEXT = 'sk-test-PLAINTEXT-0123456789';

const PROJECT: CurrentProject = {
  tenantId: 'ten_1',
  tenantName: '华东零售',
  projectId: 'prj_1',
  projectName: '华东经营分析',
  roles: ['tenant_owner'],
  access: {
    tenant_id: 'ten_1',
    project_id: 'prj_1',
    permissions: ['secret_read', 'secret_write'],
    scopes: [],
  },
};

const SECRET = {
  id: 'sec_1',
  tenant_id: 'ten_1',
  project_id: null,
  name: 'DashScope 生产',
  version: 1,
  created_by: 'u1',
  created_at: '2026-11-13T02:00:00Z',
  updated_at: '2026-11-13T02:00:00Z',
  last_used_at: null,
  references: [{ type: 'provider_account', id: 'prv_1', name: 'DashScope' }],
};

const json = (body: unknown, status: number) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json' },
  });

/** 查询缓存与变更缓存的全部内容（键、数据、变更的变量与结果）序列化成文本。 */
function cacheText(client: QueryClient): string {
  return JSON.stringify({
    queries: client
      .getQueryCache()
      .getAll()
      .map((q) => ({ key: q.queryKey, state: q.state })),
    mutations: client
      .getMutationCache()
      .getAll()
      .map((m) => m.state),
  });
}

function setup(ui: React.ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const user = userEvent.setup();
  render(
    <QueryClientProvider client={client}>
      <TooltipProvider>
        <ProjectProvider project={PROJECT}>{ui}</ProjectProvider>
      </TooltipProvider>
    </QueryClientProvider>,
  );
  return { client, user };
}

describe('凭据的写入与轮换（明文只写不读，ADR-0056）', () => {
  // 不能写成表达式体：beforeEach 返回的函数会被当成清理钩子调用。
  beforeEach(() => {
    fetchMock.mockReset();
  });
  afterEach(() => vi.clearAllMocks());

  it('写入：值是密码框，只出现在请求体里；成功后不留在页面、查询缓存与变更缓存中', async () => {
    fetchMock.mockImplementation((request) => {
      const req = request as Request;
      if (req.method === 'POST') return Promise.resolve(json(SECRET, 201));
      return Promise.resolve(json({ data: [SECRET], has_more: false, next_cursor: null }, 200));
    });
    const onCreated = vi.fn();
    const onOpenChange = vi.fn();
    const { client, user } = setup(
      <CreateSecretDialog open onOpenChange={onOpenChange} onCreated={onCreated} />,
    );

    await user.type(screen.getByRole('textbox', { name: '名称' }), 'DashScope 生产');
    const value = screen.getByLabelText('凭据值');
    expect(value).toHaveAttribute('type', 'password');
    expect(value).toHaveAttribute('autocomplete', 'off');
    await user.type(value, PLAINTEXT);
    await user.click(screen.getByRole('button', { name: '保存凭据' }));

    await waitFor(() => expect(onCreated).toHaveBeenCalledWith(SECRET));
    const post = fetchMock.mock.calls.map(([r]) => r as Request).find((r) => r.method === 'POST')!;
    expect(await post.clone().json()).toEqual({ name: 'DashScope 生产', project_id: null, value: PLAINTEXT });
    expect(onOpenChange).toHaveBeenCalledWith(false);
    expect((value as HTMLInputElement).value).toBe('');
    expect(document.body.innerHTML).not.toContain(PLAINTEXT);
    expect(cacheText(client)).not.toContain(PLAINTEXT);
    expect(client.getMutationCache().getAll()).toHaveLength(0);
  });

  it('写入失败：值也被清空，错误就地显示，需要重新输入', async () => {
    fetchMock.mockResolvedValue(
      json(
        {
          type: 'urn:meridian:error:secret.encryption_unavailable',
          title: '密钥加密服务暂时不可用',
          status: 503,
          detail: '密钥加密服务暂时不可用，稍后重试。',
          instance: '/admin/v1/tenants/ten_1/secrets',
          code: 'secret.encryption_unavailable',
          request_id: 'req_1',
        },
        503,
      ),
    );
    const { client, user } = setup(<CreateSecretDialog open onOpenChange={vi.fn()} />);
    await user.type(screen.getByRole('textbox', { name: '名称' }), 'n');
    await user.type(screen.getByLabelText('凭据值'), PLAINTEXT);
    await user.click(screen.getByRole('button', { name: '保存凭据' }));

    expect(await screen.findByText('写入失败，请重新输入凭据值')).toBeInTheDocument();
    expect(screen.getByLabelText('凭据值')).toHaveValue('');
    expect(document.body.innerHTML).not.toContain(PLAINTEXT);
    expect(cacheText(client)).not.toContain(PLAINTEXT);
  });

  it('名称已被使用：标在名称上', async () => {
    fetchMock.mockResolvedValue(
      json(
        {
          type: 'urn:meridian:error:secret.name_taken',
          title: '凭据名称已被使用',
          status: 409,
          detail: '同一范围内已有同名凭据',
          instance: '/admin/v1/tenants/ten_1/secrets',
          code: 'secret.name_taken',
          request_id: 'req_2',
        },
        409,
      ),
    );
    const { user } = setup(<CreateSecretDialog open onOpenChange={vi.fn()} />);
    await user.type(screen.getByRole('textbox', { name: '名称' }), 'dup');
    await user.type(screen.getByLabelText('凭据值'), PLAINTEXT);
    await user.click(screen.getByRole('button', { name: '保存凭据' }));
    expect(await screen.findByText('同一范围内已有同名的凭据，换一个名字。')).toBeInTheDocument();
    expect(screen.getByRole('textbox', { name: '名称' })).toHaveAttribute('aria-invalid', 'true');
  });

  it('轮换：写明引用方会改用新值，提交后不留明文', async () => {
    fetchMock.mockImplementation((request) => {
      const req = request as Request;
      if (req.method === 'POST') return Promise.resolve(json({ ...SECRET, version: 2 }, 200));
      return Promise.resolve(
        json({ data: [{ ...SECRET, version: 2 }], has_more: false, next_cursor: null }, 200),
      );
    });
    const onOpenChange = vi.fn();
    const { client, user } = setup(<RotateSecretDialog secret={SECRET} onOpenChange={onOpenChange} />);
    expect(screen.getByText('旧值立即失效、不能恢复；引用它的 1 个资源随即改用新值。')).toBeInTheDocument();
    await user.type(screen.getByLabelText('新的凭据值'), PLAINTEXT);
    await user.click(screen.getByRole('button', { name: '轮换为版本 2' }));
    await waitFor(() => expect(onOpenChange).toHaveBeenCalledWith(false));
    const post = fetchMock.mock.calls.map(([r]) => r as Request).find((r) => r.method === 'POST')!;
    expect(new URL(post.url).pathname).toBe('/admin/v1/tenants/ten_1/secrets/sec_1:rotate');
    expect(await post.clone().json()).toEqual({ value: PLAINTEXT });
    expect(document.body.innerHTML).not.toContain(PLAINTEXT);
    expect(cacheText(client)).not.toContain(PLAINTEXT);
  });
});
