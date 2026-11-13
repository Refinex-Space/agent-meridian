import { cn } from '@/lib/cn';
import type { ResourceReference } from '@/lib/api/types';

/** 引用方的类型（管理 API 的 `ResourceReference.type`）按用户的概念写。 */
export const REFERENCE_TYPE_LABEL: Record<string, string> = {
  provider_account: '供应商账号',
  model: '模型',
  tenant_settings: '租户设置',
  mcp_server: 'MCP 服务',
  agent_version: '智能体版本',
};

/** 一个引用方的名字：没有名称的（租户设置）写它是什么。 */
export function referenceName(reference: ResourceReference): string {
  if (reference.type === 'tenant_settings') return '联网搜索';
  return reference.name ?? reference.id;
}

/** 引用方的一句话概括：一个时写类型与名称，多个时写第一个与总数（"供应商账号 DashScope 等 3 个"）。 */
export function summarizeReferences(references: readonly ResourceReference[]): string {
  const [first] = references;
  if (!first) return '';
  const head = `${REFERENCE_TYPE_LABEL[first.type] ?? first.type} ${referenceName(first)}`;
  return references.length === 1 ? head : `${head} 等 ${references.length} 个`;
}

export interface ReferenceListProps {
  references: readonly ResourceReference[];
  /** 列表的可访问名称（"引用这个凭据的资源"）。 */
  label: string;
  className?: string;
}

/**
 * 引用方列表（凭据、技能、模型被哪些资源引用）：每行左侧是类型、右侧是名称，名称是机器名时等宽。删除被拒绝（409
 * `*.in_use`）与详情页的"引用"都用它。
 */
export function ReferenceList({ references, label, className }: ReferenceListProps) {
  return (
    <ul
      aria-label={label}
      className={cn('flex flex-col divide-y divide-rule border-y border-rule', className)}
    >
      {references.map((r) => (
        <li key={`${r.type}:${r.id}`} className="grid grid-cols-[112px_minmax(0,1fr)] gap-3 py-2 text-13">
          <span className="text-ink-2">{REFERENCE_TYPE_LABEL[r.type] ?? r.type}</span>
          <span className="truncate" title={r.id}>
            {referenceName(r)}
          </span>
        </li>
      ))}
    </ul>
  );
}
