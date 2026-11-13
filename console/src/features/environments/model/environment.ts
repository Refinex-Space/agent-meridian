/**
 * 环境表单的规则，与 control-plane 的 `EnvironmentConfig` 和管理 API 的 `EnvironmentRequest` 一致：名字 1 到 100 个字符；
 * `sandbox` 档必须选规格、`none` 档不能选；出网白名单至多 100 个域名，小写，`*.` 前缀匹配任意层级子域名（不含域名本身），
 * 不接受 IP、端口与路径，单标签（集群内的服务名）允许；按小写去重、保持顺序。
 */
import { z } from 'zod';

import type { Environment, EnvironmentRequest, EnvironmentTier, SandboxSize } from '@/lib/api/types';

export const MAX_ALLOWLIST = 100;

/** 一个域名标签：字母或数字开头结尾，中间可以有短横线，至多 63 个字符。 */
const LABEL = '[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?';
const DOMAIN = new RegExp(`^(?:\\*\\.)?${LABEL}(?:\\.${LABEL})*$`);
const IPV4 = /^[0-9.]+$/;

/** 资源规格（管理 API 的 `SandboxSize`）：各对应一套预部署的沙箱模板与预热池。 */
export const SIZES: readonly { value: SandboxSize; label: string; cpu: string; memory: string }[] = [
  { value: 'small', label: '小', cpu: '1 vCPU', memory: '2 GiB' },
  { value: 'medium', label: '中', cpu: '2 vCPU', memory: '4 GiB' },
  { value: 'large', label: '大', cpu: '4 vCPU', memory: '8 GiB' },
];

export const TIER_LABEL: Record<EnvironmentTier, string> = { none: '无沙箱', sandbox: '沙箱' };

/** 规格的显示："中，2 vCPU、4 GiB"。 */
export function sizeText(size: SandboxSize | null): string | null {
  const s = SIZES.find((x) => x.value === size);
  return s ? `${s.label}，${s.cpu}、${s.memory}` : null;
}

/** 规整一条白名单条目：去掉空白、转小写。 */
export function normalizeDomain(entry: string): string {
  return entry.trim().toLowerCase();
}

/** 校验一条（已规整的）白名单条目；合法时为 null。 */
export function checkDomain(domain: string): string | null {
  if (domain === '') return '写一个域名。';
  if (/^[a-z][a-z0-9+.-]*:\/\//.test(domain) || domain.includes('/'))
    return '只写域名，不带协议与路径，例如 api.example.com。';
  if (/:\d+$/.test(domain)) return '不限定端口：去掉冒号与端口号。';
  if (IPV4.test(domain) || domain.includes(':')) return '不接受 IP 地址，写域名。';
  if (domain.length > 253 || !DOMAIN.test(domain))
    return '不是合法的域名：小写字母、数字与短横线，子域名通配写成 *.example.com。';
  return null;
}

/** 粘贴的多个域名：按空白、逗号、分号与换行拆开。 */
export function splitDomains(text: string): string[] {
  return text
    .split(/[\s,;，；]+/)
    .map(normalizeDomain)
    .filter((d) => d !== '');
}

export const environmentSchema = z
  .object({
    name: z.string().trim().min(1, '写一个名字，项目内不能重复。').max(100, '名字至多 100 个字符。'),
    tier: z.enum(['none', 'sandbox']),
    size: z.enum(['small', 'medium', 'large']).nullable(),
    egress_allowlist: z.array(z.string()).max(MAX_ALLOWLIST, `白名单至多 ${MAX_ALLOWLIST} 个域名。`),
  })
  .superRefine((value, ctx) => {
    if (value.tier === 'sandbox' && value.size === null)
      ctx.addIssue({ code: 'custom', path: ['size'], message: '沙箱档要选一个资源规格。' });
    const seen = new Set<string>();
    value.egress_allowlist.forEach((domain, i) => {
      const problem = checkDomain(domain) ?? (seen.has(domain) ? '这个域名重复了。' : null);
      seen.add(domain);
      if (problem)
        ctx.addIssue({ code: 'custom', path: ['egress_allowlist', i], message: `${domain}：${problem}` });
    });
  });

export type EnvironmentForm = z.infer<typeof environmentSchema>;

export function toForm(env: Environment | null): EnvironmentForm {
  return env
    ? { name: env.name, tier: env.tier, size: env.size, egress_allowlist: [...env.egress_allowlist] }
    : { name: '', tier: 'none', size: null, egress_allowlist: [] };
}

/** 提交的请求：`none` 档不带规格（服务端拒绝）。 */
export function toRequest(form: EnvironmentForm): EnvironmentRequest {
  return {
    name: form.name.trim(),
    tier: form.tier,
    size: form.tier === 'sandbox' ? form.size : null,
    egress_allowlist: form.egress_allowlist,
  };
}

/** 档位、规格或白名单变了：保存会产生新修订（只改名字不产生）。 */
export function changesRevision(before: EnvironmentForm, after: EnvironmentForm): boolean {
  const a = toRequest(before);
  const b = toRequest(after);
  return (
    a.tier !== b.tier ||
    a.size !== b.size ||
    JSON.stringify(a.egress_allowlist) !== JSON.stringify(b.egress_allowlist)
  );
}

/** 两份白名单的增减。 */
export function allowlistDelta(before: readonly string[], after: readonly string[]) {
  return {
    added: after.filter((d) => !before.includes(d)),
    removed: before.filter((d) => !after.includes(d)),
  };
}
