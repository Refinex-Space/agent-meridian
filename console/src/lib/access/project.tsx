import { createContext, use, type ReactNode } from 'react';

import { v1Api, type V1Client } from '@/lib/api/client';
import type { MyAccess, PermissionName, Role } from '@/lib/api/types';

/** 当前项目：路由 `/projects/$projectId` 确认用户能进入后提供给外壳与页面。 */
export interface CurrentProject {
  tenantId: string;
  tenantName: string;
  projectId: string;
  projectName: string;
  /** 当前用户在项目上的角色（显示用；能否操作只看 `access`）。 */
  roles: readonly Role[];
  /** `GET /admin/v1/me/access` 的结果。 */
  access: MyAccess;
}

const ProjectContext = createContext<CurrentProject | null>(null);

export function ProjectProvider({ project, children }: { project: CurrentProject; children: ReactNode }) {
  return <ProjectContext value={project}>{children}</ProjectContext>;
}

/** 当前项目；只能在项目路由之内使用。 */
export function useProject(): CurrentProject {
  const project = use(ProjectContext);
  if (!project) throw new Error('useProject 需要在项目路由（/projects/$projectId）之内使用');
  return project;
}

/** 当前项目的公开 API 客户端（请求带 `Meridian-Project`）。 */
export function useProjectApi(): V1Client {
  return v1Api(useProject().projectId);
}

/** 当前用户在当前项目上是否有某个操作类别。 */
export function hasPermission(project: CurrentProject, permission: PermissionName): boolean {
  return project.access.permissions.includes(permission);
}
