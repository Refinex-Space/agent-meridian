/**
 * 原型外壳的身份夹具：当前用户、能进入的租户与项目、预算告警。正式外壳的数据来自 `GET /admin/v1/me`、`GET T:/projects`、
 * `GET /admin/v1/me/access` 与 `GET P:/budget-status`（`app/shell/shell-layout.tsx`），原型经 `shell/app-shell.tsx` 映射。
 */

export interface ProjectOption {
  id: string;
  name: string;
}

export interface TenantProjects {
  tenantId: string;
  tenantName: string;
  projects: readonly ProjectOption[];
}

export interface CurrentUser {
  displayName: string;
  email: string;
  /** 头像位置显示的字。 */
  initial: string;
  /** 在当前项目上的角色（显示用）。 */
  role: string;
}

export const CURRENT_USER: CurrentUser = {
  displayName: '林晓雯',
  email: 'linxiaowen@east-retail.example.com',
  initial: '林',
  role: '项目开发者',
};

export const CURRENT_PROJECT_ID = 'prj_01JB6M2Q8T4W0Y6B2D8F4H0KQ';

export const TENANTS: readonly TenantProjects[] = [
  {
    tenantId: 'tnt_01JB5H1N7R3V9X5A1C7E3G9JM',
    tenantName: '华东大区',
    projects: [
      { id: CURRENT_PROJECT_ID, name: '华东经营分析' },
      { id: 'prj_01JB6N5S1V7X3Z9B5D1F7H3KR', name: '门店运营' },
      { id: 'prj_01JB6P8V4Y0A6C2E8G4J0L6NS', name: '客服中心' },
    ],
  },
  {
    tenantId: 'tnt_01JB5J4Q0T6X2Z8B4D0F6H2KN',
    tenantName: '集团财务共享',
    projects: [{ id: 'prj_01JB6R1X7A3C9E5G1J7L3N9QT', name: '费用审核' }],
  },
];

/** 预算状态（`GET P:/budget-status` 的 `state` 与本周期用量）。 */
export interface BudgetAlert {
  state: 'ok' | 'soft_exceeded' | 'hard_exceeded';
  /** 本周期已用占硬阈值的比例，0 到 1。 */
  usedRatio: number;
  softRatio: number;
}

export const BUDGET_SOFT_EXCEEDED: BudgetAlert = { state: 'soft_exceeded', usedRatio: 0.86, softRatio: 0.8 };
