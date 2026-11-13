import { describe, expect, it } from 'vitest';

import {
  allowlistDelta,
  changesRevision,
  checkDomain,
  environmentSchema,
  splitDomains,
  toForm,
  toRequest,
} from './environment';

describe('出网白名单的校验（与 control-plane 的 EnvironmentConfig 一致）', () => {
  it.each([
    ['pypi.org', null],
    ['*.pythonhosted.org', null],
    ['erp-gateway', null],
    ['a-b.c-d.example.com', null],
  ])('合法：%s', (domain, expected) => {
    expect(checkDomain(domain)).toBe(expected);
  });

  it.each([
    ['https://pypi.org', '不带协议'],
    ['pypi.org/simple', '不带协议与路径'],
    ['pypi.org:443', '不限定端口'],
    ['10.0.0.1', '不接受 IP'],
    ['-bad.example.com', '不是合法的域名'],
    ['*.', '不是合法的域名'],
    ['a..b', '不是合法的域名'],
    [`${'a'.repeat(64)}.com`, '不是合法的域名'],
  ])('拒绝：%s', (domain, message) => {
    expect(checkDomain(domain)).toContain(message);
  });

  it('粘贴多个域名时按空白、逗号与换行拆开并转小写', () => {
    expect(splitDomains(' PyPI.org,\n*.Example.com；files.example.com ')).toEqual([
      'pypi.org',
      '*.example.com',
      'files.example.com',
    ]);
  });

  it('沙箱档要选规格，白名单逐条校验并指出重复', () => {
    const result = environmentSchema.safeParse({
      name: '数据分析',
      tier: 'sandbox',
      size: null,
      egress_allowlist: ['pypi.org', 'pypi.org', '10.0.0.1'],
    });
    expect(result.success).toBe(false);
    expect(result.error?.issues.map((i) => i.path)).toEqual([
      ['size'],
      ['egress_allowlist', 1],
      ['egress_allowlist', 2],
    ]);
  });

  it('白名单至多 100 个', () => {
    const list = Array.from({ length: 101 }, (_, i) => `h${i}.example.com`);
    const result = environmentSchema.safeParse({
      name: 'x',
      tier: 'sandbox',
      size: 'small',
      egress_allowlist: list,
    });
    expect(result.error?.issues[0]?.path).toEqual(['egress_allowlist']);
  });
});

describe('请求与修订', () => {
  it('无沙箱档不带规格', () => {
    expect(toRequest({ name: ' a ', tier: 'none', size: 'small', egress_allowlist: [] })).toEqual({
      name: 'a',
      tier: 'none',
      size: null,
      egress_allowlist: [],
    });
  });

  it('只改名字不产生新修订；改了档位、规格或白名单才产生', () => {
    const base = toForm(null);
    expect(changesRevision(base, { ...base, name: '新名字' })).toBe(false);
    expect(changesRevision(base, { ...base, tier: 'sandbox', size: 'small' })).toBe(true);
    expect(changesRevision(base, { ...base, egress_allowlist: ['pypi.org'] })).toBe(true);
  });

  it('白名单的增减', () => {
    expect(allowlistDelta(['a.com', 'b.com'], ['b.com', 'c.com'])).toEqual({
      added: ['c.com'],
      removed: ['a.com'],
    });
  });
});
