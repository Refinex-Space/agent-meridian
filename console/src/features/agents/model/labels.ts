/** 规则结果的中文名（规则表、版本差异与摘要共用）。 */
export const BEHAVIOR_LABEL: Readonly<Record<string, string>> = { allow: '放行', ask: '询问', deny: '拒绝' };

/** 权限模式的中文名与后果（选择器的提示、详情页的摘要）。 */
export const PERMISSION_MODE_LABEL: Readonly<Record<string, { label: string; hint: string }>> = {
  default: { label: '默认', hint: '没有命中放行规则的调用都要人工确认' },
  accept_edits: { label: '自动接受编辑', hint: '写文件、改文件不再确认，其余同默认' },
  explore: { label: '只读探索', hint: '只允许读类工具' },
  bypass: { label: '跳过确认', hint: '除拒绝规则外全部放行，只用于受信任的内部任务' },
  dont_ask: { label: '不询问', hint: '没有命中放行规则的调用直接拒绝，用于无人值守' },
};
