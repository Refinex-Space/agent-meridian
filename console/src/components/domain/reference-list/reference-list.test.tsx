import { render, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { ReferenceList, summarizeReferences } from './reference-list';

const refs = [
  { type: 'provider_account', id: 'prv_1', name: 'DashScope' },
  { type: 'tenant_settings', id: 'ten_1', name: null },
  { type: 'agent_version', id: 'agv_1', name: 'support v3' },
];

describe('ReferenceList', () => {
  it('按用户的概念写类型，租户设置写成联网搜索', () => {
    render(<ReferenceList references={refs} label="引用这个凭据的资源" />);
    const items = within(screen.getByRole('list', { name: '引用这个凭据的资源' })).getAllByRole('listitem');
    expect(items.map((i) => i.textContent)).toEqual([
      '供应商账号DashScope',
      '租户设置联网搜索',
      '智能体版本support v3',
    ]);
  });

  it('概括：一个写类型与名称，多个写第一个与总数', () => {
    expect(summarizeReferences([])).toBe('');
    expect(summarizeReferences(refs.slice(0, 1))).toBe('供应商账号 DashScope');
    expect(summarizeReferences(refs)).toBe('供应商账号 DashScope 等 3 个');
  });
});
