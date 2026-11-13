import { describe, expect, it } from 'vitest';

import type { TenantProjects } from '@/lib/access';

import { pickProject } from './router';

const groups: TenantProjects[] = [
  {
    tenant_id: 'ten_a',
    tenant_name: '华东大区',
    projects: [
      { id: 'prj_1', name: '华东经营分析', tenant_id: 'ten_a', tenant_name: '华东大区' },
      { id: 'prj_2', name: '门店运营', tenant_id: 'ten_a', tenant_name: '华东大区' },
    ],
  },
];

describe('首页去哪个项目', () => {
  it('上次使用的项目仍可进入时回到它，否则取第一个；没有项目时为 null', () => {
    expect(pickProject(groups, 'prj_2')).toBe('prj_2');
    expect(pickProject(groups, 'prj_removed')).toBe('prj_1');
    expect(pickProject(groups, null)).toBe('prj_1');
    expect(pickProject([], 'prj_1')).toBeNull();
  });
});
