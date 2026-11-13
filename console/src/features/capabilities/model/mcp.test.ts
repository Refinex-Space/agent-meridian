import { describe, expect, it } from 'vitest';

import type { McpServer } from '@/lib/api/types';

import { effectiveSideEffect, EMPTY_MCP_FORM, toMcpForm, toMcpRequest, validateMcpForm } from './mcp';

const SERVER: McpServer = {
  id: 'mcp_1',
  tenant_id: 'ten_1',
  project_id: 'prj_1',
  name: 'crm',
  transport: 'streamable_http',
  url: 'https://crm.example.com/mcp',
  header_secret_ids: { Authorization: 'sec_1' },
  side_effect: 'idempotent',
  tool_side_effects: { lookup_order: 'none' },
  tools: [
    { name: 'lookup_order', description: '查订单', side_effect: 'none' },
    { name: 'create_refund', description: '退款', side_effect: 'idempotent' },
  ],
  probed_at: '2026-11-13T02:00:00Z',
  last_check: null,
  created_at: '2026-11-13T02:00:00Z',
  updated_at: '2026-11-13T02:00:00Z',
};

describe('MCP 服务的表单', () => {
  it('校验服务名、地址与请求头（空行忽略、重名与缺凭据报在行上）', () => {
    expect(validateMcpForm(EMPTY_MCP_FORM)).toEqual({
      name: '小写字母开头，只用小写字母、数字与下划线，至多 32 个字符。',
      url: '写 http 或 https 开头的地址。',
    });
    const errors = validateMcpForm({
      ...EMPTY_MCP_FORM,
      name: 'crm',
      url: 'https://crm.example.com/mcp',
      headers: [
        { name: 'Authorization', secretId: 'sec_1' },
        { name: 'authorization', secretId: 'sec_2' },
        { name: 'X-Tenant', secretId: null },
        { name: 'bad header', secretId: 'sec_3' },
        { name: '', secretId: null },
      ],
    });
    expect(errors).toEqual({
      'headers.1': '请求头重复。',
      'headers.2': '选择这个请求头的凭据。',
      'headers.3': '请求头名只用字母、数字与短横线。',
    });
  });

  it('请求体去掉空行、修整地址，沿用逐个工具的标注', () => {
    const form = {
      ...toMcpForm(SERVER),
      url: ' https://crm.example.com/mcp ',
      headers: [...toMcpForm(SERVER).headers, { name: '', secretId: null }],
    };
    expect(toMcpRequest(form, SERVER.tool_side_effects)).toEqual({
      name: 'crm',
      transport: 'streamable_http',
      url: 'https://crm.example.com/mcp',
      header_secret_ids: { Authorization: 'sec_1' },
      side_effect: 'idempotent',
      tool_side_effects: { lookup_order: 'none' },
    });
  });

  it('生效的等级：单独标注，否则服务默认，都没有时按有副作用', () => {
    expect(effectiveSideEffect(SERVER, 'lookup_order')).toBe('none');
    expect(effectiveSideEffect(SERVER, 'create_refund')).toBe('idempotent');
    expect(effectiveSideEffect({ ...SERVER, side_effect: null }, 'create_refund')).toBe('non_idempotent');
  });
});
