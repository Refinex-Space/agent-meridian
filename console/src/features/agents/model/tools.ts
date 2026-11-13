/**
 * 平台工具与权限规则内容的语法（架构设计「平台工具」表）。工具名与 `agent-spec.schema.json` 的 `platform_tool` 枚举一一对应，
 * 规则内容的语法与 engine 的 `RuleSyntax` 一致：路径 glob、命令前缀、域名、资源 ID，或者没有内容级规则。
 */

export const PLATFORM_TOOLS = [
  'read_file',
  'list_files',
  'glob_files',
  'grep_files',
  'write_file',
  'edit_file',
  'bash',
  'web_search',
  'web_fetch',
  'knowledge_search',
  'spawn_subagent',
] as const;

export type PlatformTool = (typeof PLATFORM_TOOLS)[number];

/** 平台工具的中文名与补充说明（说明放进 info 提示）。 */
export const TOOL_INFO: Record<PlatformTool, { label: string; hint?: string }> = {
  read_file: { label: '读文件' },
  list_files: { label: '列目录' },
  glob_files: { label: '按模式找文件' },
  grep_files: { label: '搜索文件内容' },
  write_file: { label: '写文件' },
  edit_file: { label: '改文件' },
  bash: { label: '执行命令', hint: '只在沙箱档的环境中生效' },
  web_search: { label: '联网搜索', hint: '搜索后端由租户设置决定' },
  web_fetch: { label: '读取网页' },
  knowledge_search: { label: '检索知识库', hint: '只能检索本智能体绑定的知识库' },
  spawn_subagent: { label: '拉起子智能体', hint: '声明了子智能体时必须启用' },
};

export function isPlatformTool(name: string): name is PlatformTool {
  return (PLATFORM_TOOLS as readonly string[]).includes(name);
}

/** MCP 工具的全名 `mcp__{服务名}__{工具名}`。 */
export function mcpToolName(server: string, tool: string): string {
  return `mcp__${server}__${tool}`;
}

/** 解析 MCP 工具全名；不是 MCP 工具时为 null。 */
export function parseMcpToolName(name: string): { server: string; tool: string } | null {
  const match = /^mcp__([a-z][a-z0-9_]{0,31})__([A-Za-z0-9_-]{1,64})$/.exec(name);
  return match ? { server: match[1]!, tool: match[2]! } : null;
}

/** 规则内容的语法种类。 */
export type RuleSyntaxKind = 'path' | 'command' | 'domain' | 'knowledge_base' | 'subagent' | 'none';

export function ruleSyntaxOf(toolName: string): RuleSyntaxKind {
  switch (toolName) {
    case 'read_file':
    case 'list_files':
    case 'glob_files':
    case 'grep_files':
    case 'write_file':
    case 'edit_file':
      return 'path';
    case 'bash':
      return 'command';
    case 'web_fetch':
      return 'domain';
    case 'knowledge_search':
      return 'knowledge_base';
    case 'spawn_subagent':
      return 'subagent';
    default:
      // web_search、MCP 工具、计划模式等工具没有内容级规则。
      return 'none';
  }
}

/** 规则内容输入框的占位与提示：按所选工具给出正确的格式。 */
export const RULE_SYNTAX_HELP: Record<RuleSyntaxKind, { placeholder: string; hint: string }> = {
  path: {
    placeholder: 'reports/**',
    hint: '相对工作区根的路径 glob：** 匹配任意层目录，reports/** 也匹配 reports 本身',
  },
  command: {
    placeholder: 'git status:*',
    hint: '命令前缀：git status:* 匹配以 git status 开头的命令，不带 :* 时要求整条命令相同；复合命令的每一段都要被覆盖',
  },
  domain: {
    placeholder: 'domain:docs.example.com',
    hint: 'domain: 加域名，匹配该域名及其子域名',
  },
  knowledge_base: { placeholder: '选择知识库', hint: '只匹配检索这个知识库的调用' },
  subagent: { placeholder: '选择子智能体', hint: '只匹配拉起这个子智能体的调用' },
  none: { placeholder: '整个工具', hint: '这个工具没有内容级规则，规则作用于整个工具' },
};

const DOMAIN_LABEL = '[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?';
const RULE_DOMAIN = new RegExp(`^domain:${DOMAIN_LABEL}(?:\\.${DOMAIN_LABEL})+$`);

/**
 * 本地校验一条规则的内容（规则内容为 null 表示整工具规则，总是合法）。返回错误说明，合法时为 null。
 *
 * @param knowledgeBaseIds 本智能体绑定的知识库
 * @param subagentNames 本智能体声明的子智能体
 */
export function checkRuleContent(
  toolName: string,
  content: string | null,
  knowledgeBaseIds: readonly string[],
  subagentNames: readonly string[],
): string | null {
  if (content === null) return null;
  const value = content.trim();
  if (value === '') return '规则内容为空时请选择"整个工具"。';
  switch (ruleSyntaxOf(toolName)) {
    case 'path':
      if (value.startsWith('/')) return '写相对工作区根的路径，不以 / 开头，例如 reports/**。';
      if (value.split('/').includes('..')) return '路径不能包含 ..，规则只作用于工作区内。';
      return null;
    case 'command':
      return /^:\*$/.test(value) ? '写出命令前缀，例如 git status:*。' : null;
    case 'domain':
      return RULE_DOMAIN.test(value.toLowerCase())
        ? null
        : '写成 domain: 加域名，例如 domain:docs.example.com。';
    case 'knowledge_base':
      return knowledgeBaseIds.includes(value) ? null : '选择本智能体绑定的知识库。';
    case 'subagent':
      return subagentNames.includes(value) ? null : '选择本智能体声明的子智能体。';
    case 'none':
      return '这个工具没有内容级规则，只能作用于整个工具。';
  }
}
