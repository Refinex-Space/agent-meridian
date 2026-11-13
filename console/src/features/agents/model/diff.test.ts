import { describe, expect, it } from 'vitest';

import { describeChanges, isSnapshotPath, labelOf, lineDiff } from './diff';

const names = new Map([
  ['mdl_plus', 'qwen-plus'],
  ['mdl_max', 'qwen-max'],
  ['kb_a', '季度经营月报'],
  ['mcp_crm', 'crm'],
]);

const base = {
  name: 'assistant',
  agent_version_id: 'agv_1',
  model: { model_id: 'mdl_plus' },
  system_prompt: '第一行\n第二行',
  tools: ['read_file', 'web_fetch', 'spawn_subagent'],
  mcp_servers: [{ mcp_server_id: 'mcp_crm', name: 'crm', url: 'https://a', enabled_tools: [] }],
  subagents: [{ name: 'reader', tools: ['read_file'] }],
  knowledge: { knowledge_base_ids: [] },
  permissions: { mode: 'default', rules: [] },
};

const target = {
  model: { model_id: 'mdl_max' },
  system_prompt: '第一行\n改过的第二行\n第三行',
  tools: ['read_file', 'bash', 'spawn_subagent'],
  mcp_servers: [{ mcp_server_id: 'mcp_crm', enabled_tools: [] }],
  subagents: [{ name: 'reader', tools: ['read_file', 'bash'] }],
  knowledge: { knowledge_base_ids: ['kb_a'] },
  permissions: {
    mode: 'default',
    rules: [{ tool_name: 'bash', rule_content: 'git status:*', behavior: 'allow' }],
  },
};

describe('版本差异的呈现', () => {
  it('快照字段在草稿参与对比时不显示，列表合成增减，ID 换成名字，提示词逐行比较', () => {
    const rows = describeChanges(
      [
        { path: '/agent_version_id', kind: 'removed', before: 'agv_1', after: null },
        { path: '/knowledge/knowledge_base_ids/0', kind: 'added', before: null, after: 'kb_a' },
        { path: '/mcp_servers/0/name', kind: 'removed', before: 'crm', after: null },
        { path: '/mcp_servers/0/url', kind: 'removed', before: 'https://a', after: null },
        { path: '/model/model_id', kind: 'changed', before: 'mdl_plus', after: 'mdl_max' },
        { path: '/name', kind: 'removed', before: 'assistant', after: null },
        {
          path: '/permissions/rules/0',
          kind: 'added',
          before: null,
          after: {
            tool_name: 'bash',
            rule_content: 'git status:*',
            behavior: 'allow',
            layer: 'agent_version',
          },
        },
        { path: '/subagents/0/tools/1', kind: 'added', before: null, after: 'bash' },
        { path: '/system_prompt', kind: 'changed', before: base.system_prompt, after: target.system_prompt },
        { path: '/tools/1', kind: 'changed', before: 'web_fetch', after: 'bash' },
      ],
      base,
      target,
      { hideSnapshot: true, names },
    );
    expect(rows.map((r) => r.label)).toEqual([
      '可检索的知识库',
      '主模型',
      '权限规则',
      '子智能体 reader 的可用工具',
      '系统提示词',
      '平台工具',
    ]);
    expect(rows[0]?.list).toEqual({ added: ['季度经营月报'], removed: [] });
    expect(rows[1]).toMatchObject({ before: 'qwen-plus', after: 'qwen-max' });
    expect(rows[2]?.after).toBe('放行 bash(git status:*)');
    expect(rows[3]?.list).toEqual({ added: ['bash'], removed: [] });
    expect(rows[4]?.lines).toEqual([
      { kind: 'same', text: '第一行' },
      { kind: 'removed', text: '第二行' },
      { kind: 'added', text: '改过的第二行' },
      { kind: 'added', text: '第三行' },
    ]);
    expect(rows[5]?.list).toEqual({ added: ['bash'], removed: ['web_fetch'] });
  });

  it('两个已发布版本对比时，快照字段的变化照常显示（端点变了）', () => {
    const rows = describeChanges(
      [{ path: '/mcp_servers/0/url', kind: 'changed', before: 'https://a', after: 'https://b' }],
      base,
      base,
      { hideSnapshot: false, names },
    );
    expect(rows).toHaveLength(1);
    expect(rows[0]?.label).toBe('MCP 服务 crm');
  });

  it('列表只是换了顺序时不算差异', () => {
    const rows = describeChanges(
      [
        { path: '/tools/0', kind: 'changed', before: 'read_file', after: 'web_fetch' },
        { path: '/tools/1', kind: 'changed', before: 'web_fetch', after: 'read_file' },
      ],
      { tools: ['read_file', 'web_fetch'] },
      { tools: ['web_fetch', 'read_file'] },
      { hideSnapshot: true, names },
    );
    expect(rows).toEqual([]);
  });

  it('字段名按用户的概念', () => {
    expect(labelOf('/mcp_servers/0/enabled_tools', base, target, names)).toBe('MCP 服务 crm 启用的工具');
    expect(labelOf('/subagents/0/timeout', base, target, names)).toBe('子智能体 reader 的超时');
    expect(labelOf('/knowledge/prefetch_top_k', base, target, names)).toBe('注入的切片数');
    expect(isSnapshotPath('/skills/0/name')).toBe(true);
    expect(isSnapshotPath('/skills/0/version')).toBe(false);
  });

  it('逐行比较：相同的文本没有增删', () => {
    expect(lineDiff('a\nb', 'a\nb').every((l) => l.kind === 'same')).toBe(true);
  });
});
