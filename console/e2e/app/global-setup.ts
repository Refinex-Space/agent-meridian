import createClient from 'openapi-fetch';

import type { paths } from '../../src/lib/api/admin';
import type { paths as KnowledgePaths } from '../../src/lib/api/v1';

import { CONTROL_PLANE, FIXTURE, KNOWLEDGE, passwordToken, SCRIPTED_MODEL_URL, USERS } from './support';

/**
 * 端到端用例的数据（可重复执行，已存在的不重复创建）：平台管理员 dev-admin 建一个租户（自己是所有者），两个项目，
 * 把 dev-developer 设为两个项目的项目开发者、dev-viewer 设为第一个项目的项目观察者，四级预算设为不限（新租户默认零预算，
 * 否则外壳会显示预算用完的横幅）；T22 起另建模型目录（脚本化模型服务）、技能与知识库。租户与项目的 ID 经环境变量交给用例。
 */
export default async function globalSetup(): Promise<void> {
  const token = await passwordToken(USERS.admin.username);
  const api = createClient<paths>({ baseUrl: CONTROL_PLANE, headers: { Authorization: `Bearer ${token}` } });

  const fail = (what: string, response: Response, error: unknown): never => {
    throw new Error(
      `${what}失败：HTTP ${response.status} ${JSON.stringify(error)}（先运行 scripts/dev/console-e2e-env.sh up）`,
    );
  };

  const tenants = await api.GET('/admin/v1/tenants', { params: { query: { limit: 100 } } });
  if (!tenants.data) return fail('列出租户', tenants.response, tenants.error);
  let tenant = tenants.data.data.find((t) => t.name === FIXTURE.tenant);
  if (!tenant) {
    const created = await api.POST('/admin/v1/tenants', {
      body: { name: FIXTURE.tenant, owner: { subject: USERS.admin.subject } },
    });
    if (!created.data) return fail('创建租户', created.response, created.error);
    tenant = created.data;
  }
  const tenantId = tenant.id;
  const path = { tenant_id: tenantId };

  const projects = await api.GET('/admin/v1/tenants/{tenant_id}/projects', {
    params: { path, query: { limit: 100 } },
  });
  if (!projects.data) return fail('列出项目', projects.response, projects.error);
  const projectIds: string[] = [];
  for (const name of FIXTURE.projects) {
    let project = projects.data.data.find((p) => p.name === name);
    if (!project) {
      const created = await api.POST('/admin/v1/tenants/{tenant_id}/projects', {
        params: { path },
        body: { name },
      });
      if (!created.data) return fail(`创建项目 ${name}`, created.response, created.error);
      project = created.data;
    }
    projectIds.push(project.id);
  }
  const [main, other] = projectIds as [string, string];

  const members = await api.GET('/admin/v1/tenants/{tenant_id}/members', {
    params: { path, query: { limit: 100 } },
  });
  if (!members.data) return fail('列出成员', members.response, members.error);
  const memberIds = new Map(members.data.data.map((m) => [m.subject, m.id]));
  const ensureMember = async (user: (typeof USERS)[keyof typeof USERS]) => {
    const existing = memberIds.get(user.subject);
    if (existing) return existing;
    const created = await api.POST('/admin/v1/tenants/{tenant_id}/members', {
      params: { path },
      body: { subject: user.subject, email: user.email, display_name: user.name },
    });
    if (!created.data) return fail(`添加成员 ${user.username}`, created.response, created.error);
    return created.data.id;
  };
  const bindings = await api.GET('/admin/v1/tenants/{tenant_id}/role-bindings', {
    params: { path, query: { limit: 100 } },
  });
  if (!bindings.data) return fail('列出角色绑定', bindings.response, bindings.error);
  const existingBindings = bindings.data.data;
  const ensureRole = async (
    memberId: string,
    role: 'project_developer' | 'project_viewer',
    projectId: string,
  ) => {
    if (
      existingBindings.some((b) => b.member_id === memberId && b.role === role && b.project_id === projectId)
    )
      return;
    const created = await api.POST('/admin/v1/tenants/{tenant_id}/role-bindings', {
      params: { path },
      body: { member_id: memberId, role, project_id: projectId },
    });
    if (!created.data) fail(`授予 ${role}`, created.response, created.error);
  };
  const developer = await ensureMember(USERS.developer);
  const viewer = await ensureMember(USERS.viewer);
  await ensureRole(developer, 'project_developer', main);
  await ensureRole(developer, 'project_developer', other);
  await ensureRole(viewer, 'project_viewer', main);

  const budgets = await api.GET('/admin/v1/tenants/{tenant_id}/budgets', {
    params: { path, query: { limit: 100 } },
  });
  if (!budgets.data) return fail('列出预算', budgets.response, budgets.error);
  for (const level of ['tenant', 'project', 'api_key', 'session'] as const) {
    if (budgets.data.data.some((b) => b.level === level && b.scope_id === null)) continue;
    const created = await api.POST('/admin/v1/tenants/{tenant_id}/budgets', {
      params: { path },
      body: { level, hard_limit_tokens: null },
    });
    if (!created.data) fail(`设置${level}预算`, created.response, created.error);
  }

  // T22：智能体页用到的模型目录、技能与知识库。runtime 读 control-plane 的目录（console-e2e-env.sh），模型指向
  // Compose 网络中的脚本化模型服务；对话模型两个（主模型与备用、子智能体），Embedding 模型给知识库。
  const accounts = await api.GET('/admin/v1/tenants/{tenant_id}/provider-accounts', {
    params: { path, query: { limit: 100 } },
  });
  if (!accounts.data) return fail('列出供应商账号', accounts.response, accounts.error);
  let account = accounts.data.data.find((a) => a.name === FIXTURE.providerAccount);
  if (!account) {
    const created = await api.POST('/admin/v1/tenants/{tenant_id}/provider-accounts', {
      params: { path },
      body: { name: FIXTURE.providerAccount, provider: 'openai_compatible', base_url: SCRIPTED_MODEL_URL },
    });
    if (!created.data) return fail('创建供应商账号', created.response, created.error);
    account = created.data;
  }
  const models = await api.GET('/admin/v1/tenants/{tenant_id}/models', {
    params: { path, query: { limit: 100 } },
  });
  if (!models.data) return fail('列出模型', models.response, models.error);
  const modelIds: Record<string, string> = {};
  for (const model of FIXTURE.models) {
    const existing = models.data.data.find((m) => m.name === model.name);
    if (existing) {
      modelIds[model.name] = existing.id;
      continue;
    }
    const created = await api.POST('/admin/v1/tenants/{tenant_id}/models', {
      params: { path },
      body: {
        provider_account_id: account.id,
        name: model.name,
        kind: model.kind,
        upstream_model: model.upstream,
        context_window: model.kind === 'chat' ? 131_072 : 8192,
        max_output_tokens: model.kind === 'chat' ? 2048 : 1,
        capabilities: { tool_calls: model.kind === 'chat', vision: false, reasoning: false },
        ...(model.kind === 'embedding' ? { max_batch_size: 20 } : {}),
      },
    });
    if (!created.data) return fail(`创建模型 ${model.name}`, created.response, created.error);
    modelIds[model.name] = created.data.id;
  }
  const projectPath = { tenant_id: tenantId, project_id: main };
  const skills = await api.GET('/admin/v1/tenants/{tenant_id}/projects/{project_id}/skills', {
    params: { path: projectPath, query: { limit: 100 } },
  });
  if (!skills.data) return fail('列出技能', skills.response, skills.error);
  if (!skills.data.data.some((s) => s.name === FIXTURE.skill)) {
    const created = await api.POST('/admin/v1/tenants/{tenant_id}/projects/{project_id}/skills', {
      params: { path: projectPath },
      body: {
        files: [
          {
            path: 'SKILL.md',
            content: `---\nname: ${FIXTURE.skill}\ndescription: 按经营分析的口径整理周报。\n---\n\n先汇总本周的销售额与毛利，再列出下滑的品类。\n`,
          },
        ],
      },
    });
    if (!created.data) fail('上传技能', created.response, created.error);
  }
  const knowledge = createClient<KnowledgePaths>({
    baseUrl: KNOWLEDGE,
    headers: { Authorization: `Bearer ${token}`, 'Meridian-Project': main },
  });
  const bases = await knowledge.GET('/v1/knowledge-bases', { params: { query: { limit: 100 } } });
  if (!bases.data) return fail('列出知识库', bases.response, bases.error);
  if (!bases.data.data.some((k) => k.name === FIXTURE.knowledgeBase)) {
    const created = await knowledge.POST('/v1/knowledge-bases', {
      body: { name: FIXTURE.knowledgeBase, embedding_model: modelIds['scripted-embedding']! },
    });
    if (!created.data) fail('创建知识库', created.response, created.error);
  }

  process.env['E2E_TENANT_ID'] = tenantId;
  process.env['E2E_PROJECT_ID'] = main;
  process.env['E2E_OTHER_PROJECT_ID'] = other;
}
