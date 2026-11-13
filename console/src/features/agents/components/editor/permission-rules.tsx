import { useId, useState } from 'react';

import { fieldAria } from '@/components/domain/form-field';
import { StatusBadge } from '@/components/domain/status-badge';
import { Button } from '@/components/ui/button';
import { InfoIcon, PlusIcon, TrashIcon } from '@/components/ui/glyphs';
import { Icon } from '@/components/ui/icon';
import { Input } from '@/components/ui/input';
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectLabel,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';

import { BEHAVIOR_LABEL } from '../../model/labels';
import type { PermissionRule, RuleBehavior } from '../../model/spec';
import { checkRuleContent, RULE_SYNTAX_HELP, ruleSyntaxOf } from '../../model/tools';

/** 规则结果的状态符号：放行是成功，询问是等待处理，拒绝是失败。 */
export const BEHAVIOR: Record<
  RuleBehavior,
  { kind: 'success' | 'requires_action' | 'failure'; label: string }
> = {
  allow: { kind: 'success', label: BEHAVIOR_LABEL['allow']! },
  ask: { kind: 'requires_action', label: BEHAVIOR_LABEL['ask']! },
  deny: { kind: 'failure', label: BEHAVIOR_LABEL['deny']! },
};

/** 规则的工具选项，按来源分组（平台工具、MCP 工具）。 */
export interface ToolChoiceGroup {
  label: string;
  tools: readonly string[];
}

export interface PermissionRulesProps {
  /** 规则表的 id（字段锚点）；新增一行的控件以它为前缀。 */
  id: string;
  /** 读屏名称（"规则"、"reader 的放行规则"）。 */
  label: string;
  rules: readonly PermissionRule[];
  onChange: (rules: PermissionRule[]) => void;
  toolGroups: readonly ToolChoiceGroup[];
  /** 本智能体绑定的知识库（knowledge_search 的规则内容）。 */
  knowledgeBases: readonly { id: string; name: string }[];
  /** 本智能体声明的子智能体（spawn_subagent 的规则内容）。 */
  subagents: readonly string[];
  /** 只能写哪几种结果（子智能体只有放行规则）。 */
  behaviors?: readonly RuleBehavior[];
  readOnly?: boolean;
  /** 已有规则的错误（保存或发布时的校验）。 */
  error?: string | undefined;
}

const WHOLE = '__whole__';

/**
 * 权限规则编辑器：已有规则是一张表（工具、规则内容、结果），下方一行新增。规则内容的写法随所选工具变化：文件类工具是路径 glob，
 * bash 是命令前缀，web_fetch 是 `domain:` 加域名，knowledge_search 与 spawn_subagent 从绑定的知识库、声明的子智能体中选，
 * 其余工具只有整工具规则；添加前就地校验格式。
 */
export function PermissionRules({
  id,
  label,
  rules,
  onChange,
  toolGroups,
  knowledgeBases,
  subagents,
  behaviors = ['allow', 'ask', 'deny'],
  readOnly = false,
  error,
}: PermissionRulesProps) {
  const firstTool = toolGroups.find((g) => g.tools.length > 0)?.tools[0] ?? '';
  const [tool, setTool] = useState(firstTool);
  const [content, setContent] = useState('');
  const [behavior, setBehavior] = useState<RuleBehavior>(behaviors[0] ?? 'allow');
  const [draftError, setDraftError] = useState<string | null>(null);
  const hintId = useId();
  const selectedTool = tool || firstTool;
  const syntax = ruleSyntaxOf(selectedTool);
  const help = RULE_SYNTAX_HELP[syntax];
  const kbName = (kbId: string) => knowledgeBases.find((k) => k.id === kbId)?.name ?? kbId;

  const add = () => {
    const ruleContent =
      syntax === 'none' || content.trim() === '' || content === WHOLE ? null : content.trim();
    const problem = checkRuleContent(
      selectedTool,
      ruleContent,
      knowledgeBases.map((k) => k.id),
      subagents,
    );
    if (problem) {
      setDraftError(problem);
      return;
    }
    if (
      rules.some(
        (r) => r.tool_name === selectedTool && r.rule_content === ruleContent && r.behavior === behavior,
      )
    ) {
      setDraftError('已有同样的规则。');
      return;
    }
    onChange([
      ...rules,
      { tool_name: selectedTool, rule_content: ruleContent, behavior, layer: 'agent_version' },
    ]);
    setContent('');
    setDraftError(null);
  };

  const contentControl = () => {
    const contentId = `${id}-new-content`;
    const aria = { ...fieldAria(contentId, draftError), 'aria-label': '规则内容' };
    if (syntax === 'knowledge_base' || syntax === 'subagent') {
      const options =
        syntax === 'knowledge_base'
          ? knowledgeBases.map((k) => ({ value: k.id, label: k.name }))
          : subagents.map((s) => ({ value: s, label: s }));
      return (
        <Select value={content || WHOLE} onValueChange={(v) => setContent(v === WHOLE ? '' : v)}>
          <SelectTrigger id={contentId} {...aria}>
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value={WHOLE}>整个工具</SelectItem>
            {options.map((o) => (
              <SelectItem key={o.value} value={o.value}>
                {o.label}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      );
    }
    return (
      <Input
        id={contentId}
        value={syntax === 'none' ? '' : content}
        disabled={syntax === 'none'}
        placeholder={syntax === 'none' ? help.placeholder : `${help.placeholder}（留空为整个工具）`}
        className="font-mono text-12"
        onChange={(e) => {
          setContent(e.target.value);
          setDraftError(null);
        }}
        onKeyDown={(e) => {
          if (e.key === 'Enter') {
            e.preventDefault();
            add();
          }
        }}
        {...aria}
      />
    );
  };

  return (
    <div className="flex flex-col gap-2">
      <Table
        id={id}
        aria-label={label}
        className="table-fixed"
        aria-describedby={error ? `${id}-error` : undefined}
      >
        <colgroup>
          <col style={{ width: '34%' }} />
          <col />
          <col style={{ width: 96 }} />
          <col style={{ width: 48 }} />
        </colgroup>
        <TableHeader>
          <TableRow className="hover:bg-transparent">
            <TableHead>工具</TableHead>
            <TableHead>规则内容</TableHead>
            <TableHead>结果</TableHead>
            <TableHead>
              <span className="sr-only">操作</span>
            </TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {rules.length === 0 && (
            <TableRow className="hover:bg-transparent">
              <TableCell colSpan={4} className="text-ink-2">
                还没有规则
              </TableCell>
            </TableRow>
          )}
          {rules.map((rule, index) => (
            <TableRow key={`${rule.tool_name}-${rule.rule_content ?? ''}-${rule.behavior}-${index}`}>
              <TableCell className="truncate font-mono text-12">{rule.tool_name}</TableCell>
              <TableCell className="truncate font-mono text-12">
                {rule.rule_content === null ? (
                  <span className="font-sans text-13 text-ink-2">整个工具</span>
                ) : rule.tool_name === 'knowledge_search' ? (
                  <span className="font-sans text-13">{kbName(rule.rule_content)}</span>
                ) : (
                  rule.rule_content
                )}
              </TableCell>
              <TableCell>
                <StatusBadge
                  of="custom"
                  kind={BEHAVIOR[rule.behavior].kind}
                  label={BEHAVIOR[rule.behavior].label}
                />
              </TableCell>
              <TableCell className="text-right">
                {!readOnly && (
                  <Button
                    size="icon-sm"
                    variant="ghost"
                    aria-label={`删除规则 ${rule.tool_name}${rule.rule_content ? `(${rule.rule_content})` : ''}`}
                    onClick={() => onChange(rules.filter((_, i) => i !== index))}
                  >
                    <Icon glyph={TrashIcon} />
                  </Button>
                )}
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
      {error && (
        <p id={`${id}-error`} className="text-12 text-danger">
          {error}
        </p>
      )}
      {!readOnly && (
        <div role="group" aria-label={`新增${label}`} className="flex flex-col gap-2">
          <div className="grid grid-cols-[minmax(0,34%)_minmax(0,1fr)_96px_auto] items-center gap-2">
            <Select
              value={selectedTool}
              onValueChange={(v) => {
                setTool(v);
                setContent('');
                setDraftError(null);
              }}
            >
              <SelectTrigger aria-label="规则作用的工具" className="font-mono text-12">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {toolGroups
                  .filter((g) => g.tools.length > 0)
                  .map((group) => (
                    <SelectGroup key={group.label}>
                      <SelectLabel>{group.label}</SelectLabel>
                      {group.tools.map((t) => (
                        <SelectItem key={t} value={t} className="font-mono text-12">
                          {t}
                        </SelectItem>
                      ))}
                    </SelectGroup>
                  ))}
              </SelectContent>
            </Select>
            <div className="flex min-w-0 items-center gap-1">
              <div className="min-w-0 flex-1">{contentControl()}</div>
              <Tooltip>
                <TooltipTrigger asChild>
                  <button
                    type="button"
                    aria-label="规则内容的写法"
                    aria-describedby={hintId}
                    className="inline-flex rounded-control text-ink-2 hover:text-ink"
                  >
                    <Icon glyph={InfoIcon} />
                  </button>
                </TooltipTrigger>
                <TooltipContent id={hintId} className="max-w-80">
                  {help.hint}
                </TooltipContent>
              </Tooltip>
            </div>
            <Select value={behavior} onValueChange={(v) => setBehavior(v as RuleBehavior)}>
              <SelectTrigger aria-label="命中后的结果">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {behaviors.map((b) => (
                  <SelectItem key={b} value={b}>
                    {BEHAVIOR[b].label}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
            <Button icon={<Icon glyph={PlusIcon} />} onClick={add} disabled={!selectedTool}>
              添加规则
            </Button>
          </div>
          {draftError && (
            <p id={`${id}-new-content-error`} className="text-12 text-danger">
              {draftError}
            </p>
          )}
        </div>
      )}
    </div>
  );
}
