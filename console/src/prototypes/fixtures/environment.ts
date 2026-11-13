/**
 * 环境编辑的视图模型。正式数据来自管理 API `GET/PUT P:/environments/{id}`（T22 接入时映射）。这里不是 API 类型。
 */

export type Tier = 'none' | 'sandbox';
export type SandboxSize = 'small' | 'medium' | 'large';

export interface EnvironmentForm {
  name: string;
  description: string;
  tier: Tier;
  size: SandboxSize;
  /** 出网白名单：域名，或 `*.` 开头的子域通配。 */
  egressAllowlist: string[];
}

export interface EnvironmentMeta {
  id: string;
  revision: number;
  /** 使用这个环境的智能体与进行中的会话：保存后新会话用新修订，进行中的会话不变。 */
  agents: number;
  activeSessions: number;
  savedAt: string;
}

/** 资源规格：每种对应一套预部署的沙箱模板与预热池（架构设计「领域模型·环境」）。 */
export const SIZES: readonly {
  value: SandboxSize;
  label: string;
  cpu: string;
  memory: string;
  disk: string;
}[] = [
  { value: 'small', label: '小', cpu: '1 vCPU', memory: '2 GiB', disk: '5 GiB' },
  { value: 'medium', label: '中', cpu: '2 vCPU', memory: '4 GiB', disk: '10 GiB' },
  { value: 'large', label: '大', cpu: '4 vCPU', memory: '8 GiB', disk: '20 GiB' },
];

export const PROD_READONLY: EnvironmentForm = {
  name: '生产只读',
  description: '经营分析类智能体使用：只读挂载报表脚本，出网只到内部数据服务',
  tier: 'sandbox',
  size: 'medium',
  egressAllowlist: [
    'erp-gateway.east-retail.example.com',
    'bi.east-retail.example.com',
    '*.oss-cn-hangzhou.aliyuncs.com',
    'pypi.tuna.tsinghua.edu.cn',
  ],
};

export const PROD_READONLY_META: EnvironmentMeta = {
  id: 'env_01JBZ7Q3M8T2V6X0B4D9F5H1KN',
  revision: 3,
  agents: 2,
  activeSessions: 9,
  savedAt: '2026-11-11T07:18:00.000Z',
};
