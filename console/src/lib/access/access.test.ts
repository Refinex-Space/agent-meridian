import { QueryClient } from '@tanstack/react-query';
import { afterEach, describe, expect, it, vi } from 'vitest';

import type { Me, MyAccess } from '@/lib/api/types';

import { describeRoles, permissionReason } from './permission';
import type { CurrentProject } from './project';
import { rolesInProject } from './queries';

const ME: Me = {
  subject: 'sub-1',
  email: 'd@x.local',
  platform_admin: false,
  memberships: [
    {
      tenant_id: 'ten_a',
      tenant_name: '华东大区',
      member_id: 'mbr_a',
      role_bindings: [
        { role_binding_id: 'rb_1', role: 'project_viewer', project_id: 'prj_1' },
        { role_binding_id: 'rb_2', role: 'approver', project_id: 'prj_1' },
        { role_binding_id: 'rb_3', role: 'project_developer', project_id: 'prj_2' },
      ],
    },
    {
      tenant_id: 'ten_b',
      tenant_name: '集团财务共享',
      member_id: 'mbr_b',
      role_bindings: [{ role_binding_id: 'rb_4', role: 'tenant_admin', project_id: null }],
    },
  ],
};

const ACCESS: MyAccess = {
  tenant_id: 'ten_a',
  project_id: 'prj_1',
  permissions: ['session_read'],
  scopes: [],
};

describe('角色与无权限的说明', () => {
  it('项目中的角色包括租户级角色与绑定到该项目的角色，按权限从大到小显示', () => {
    expect(describeRoles(rolesInProject(ME, 'ten_a', 'prj_1'))).toBe('审批人、项目观察者');
    expect(describeRoles(rolesInProject(ME, 'ten_b', 'prj_9'))).toBe('租户管理员');
    expect(rolesInProject(ME, 'ten_c', 'prj_1')).toEqual([]);
  });

  it('说明写明当前角色与不能做的事', () => {
    const project: CurrentProject = {
      tenantId: 'ten_a',
      tenantName: '华东大区',
      projectId: 'prj_1',
      projectName: '华东经营分析',
      roles: ['project_viewer'],
      access: ACCESS,
    };
    expect(permissionReason(project, '新建智能体')).toBe(
      '你在华东经营分析中是项目观察者，不能新建智能体。请联系项目管理员授予所需的角色。',
    );
    expect(permissionReason({ ...project, roles: [] }, '新建智能体')).toBe(
      '你在华东经营分析中没有角色，不能新建智能体。请联系项目管理员授予所需的角色。',
    );
  });
});

describe('能进入的项目', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('有租户级角色时是租户的全部项目，只有项目级角色时只是绑定的项目；没有项目的租户不出现', async () => {
    const pages: Record<string, unknown> = {
      ten_a: {
        data: [
          { id: 'prj_1', tenant_id: 'ten_a', name: '门店运营' },
          { id: 'prj_2', tenant_id: 'ten_a', name: '华东经营分析' },
          { id: 'prj_3', tenant_id: 'ten_a', name: '客服中心' },
        ],
        has_more: false,
        next_cursor: null,
      },
      ten_b: {
        data: [{ id: 'prj_9', tenant_id: 'ten_b', name: '费用审核' }],
        has_more: false,
        next_cursor: null,
      },
    };
    vi.stubGlobal(
      'fetch',
      vi.fn((request: Request) => {
        const tenant = /tenants\/([^/]+)\/projects/.exec(request.url)?.[1] ?? '';
        return Promise.resolve(Response.json(pages[tenant]));
      }),
    );
    vi.resetModules();
    const { projectsQuery } = await import('./queries');
    const groups = await new QueryClient().fetchQuery(projectsQuery(ME));
    expect(groups.map((g) => [g.tenant_name, g.projects.map((p) => p.name)])).toEqual([
      ['华东大区', ['华东经营分析', '门店运营']],
      ['集团财务共享', ['费用审核']],
    ]);
    expect(groups[0]?.projects[0]).toEqual({
      id: 'prj_2',
      name: '华东经营分析',
      tenant_id: 'ten_a',
      tenant_name: '华东大区',
    });
  });
});
