import { describe, expect, it } from 'vitest';

import { anchorOf, fieldId, groupOf, ROOT_ANCHOR, sortAnchors, splitField } from '../../model/fields';
import { defaultSpec, defaultSubagent } from '../../model/spec';

import { validateAgentForm } from './use-agent-editor';

const context = { mcpServerNames: new Map<string, string>() };

describe('错误定位到字段（T22-A02）', () => {
  it('服务端的字段名映射到显示错误的字段', () => {
    const cases: [string, string][] = [
      ['name', 'name'],
      ['spec.model.model_id', 'spec.model.model_id'],
      ['spec.model', 'spec.model.model_id'],
      ['spec.tools', 'spec.tools'],
      ['spec.tools.3', 'spec.tools'],
      ['spec.subagents.0.tools.1', 'spec.subagents.0.tools'],
      ['spec.subagents.1.model.fallback_model_id', 'spec.subagents.1.model.fallback_model_id'],
      ['spec.subagents.0.skills.0', 'spec.subagents.0.skills'],
      ['spec.subagents.2.permissions.rules.0.rule_content', 'spec.subagents.2.permissions'],
      ['spec.knowledge.knowledge_base_ids.0', 'spec.knowledge.knowledge_base_ids'],
      ['spec.knowledge.query_rewrite_model', 'spec.knowledge.query_rewrite_model'],
      ['spec.mcp_servers.0.enabled_tools.2', 'spec.mcp_servers.0'],
      ['spec.permissions.rules.4', 'spec.permissions.rules'],
      ['spec.compaction', 'spec.compaction.reserved_tokens'],
      ['spec', ROOT_ANCHOR],
      ['request', ROOT_ANCHOR],
    ];
    for (const [field, anchor] of cases) expect(anchorOf(splitField(field)), field).toBe(anchor);
  });

  it('锚点对应分组与控件 id，按分组顺序排列', () => {
    expect(groupOf('spec.subagents.0.tools')).toBe('subagents');
    expect(groupOf('spec.mcp_servers.0')).toBe('tools');
    expect(groupOf('spec.compaction.reserved_tokens')).toBe('runtime');
    expect(fieldId('spec.subagents.0.tools')).toBe('field-spec-subagents-0-tools');
    expect(
      sortAnchors(['spec.max_iters', 'spec.subagents.0.timeout', 'name', 'spec.model.temperature']),
    ).toEqual(['name', 'spec.model.temperature', 'spec.subagents.0.timeout', 'spec.max_iters']);
  });

  it('本地校验的结构错误与跨字段规则都落到锚点上', () => {
    const spec = {
      ...defaultSpec('mdl_a'),
      model: { ...defaultSpec('mdl_a').model, temperature: 3 },
      subagents: [
        { ...defaultSubagent('reader', 'mdl_a'), description: '阅读', tools: ['bash'], timeout: 'PT20M' },
      ],
    };
    const errors = validateAgentForm({ name: 'Bad Name', description: '', spec }, context);
    expect(Object.keys(errors).sort()).toEqual([
      'name',
      'spec.model.temperature',
      'spec.subagents.0.timeout',
      'spec.subagents.0.tools',
      'spec.tools',
    ]);
    expect(errors['spec.subagents.0.tools']).toContain('bash 不在本智能体的工具中');
  });

  it('合法的草稿没有错误', () => {
    expect(
      validateAgentForm({ name: 'assistant', description: '', spec: defaultSpec('mdl_a') }, context),
    ).toEqual({});
  });
});
