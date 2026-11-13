import type { PendingAction, Schemas } from '@/lib/api/types';

export const ACTION_TYPE_LABEL: Record<Schemas['PendingActionType'], string> = {
  tool_confirmation: '工具确认',
  plan_exit: '退出计划模式',
  custom_tool_result: '自定义工具',
  recovery_confirmation: '恢复确认',
};

/** 到截止时间仍未回应时的处理（`PendingAction.expires_at` 的说明），接在"28 分钟后"之类的时间后面。 */
export const ON_TIMEOUT: Record<Schemas['PendingActionType'], string> = {
  tool_confirmation: '视为拒绝',
  plan_exit: '视为拒绝',
  custom_tool_result: '以超时错误回传',
  recovery_confirmation: '视为跳过',
};

/** MCP 工具显示为"服务/工具"。 */
export function toolLabel(name: string): string {
  const mcp = /^mcp__(.+?)__(.+)$/.exec(name);
  return mcp ? `${mcp[1]}/${mcp[2]}` : name;
}

/**
 * 一行看懂"要批准的是什么"：命令、路径、地址、查询，工单类取单号与目标状态，计划取第一步；其余给出紧凑的 JSON。
 */
export function inputSummary(action: Pick<PendingAction, 'input' | 'tool_name'>): string {
  const input = action.input;
  const text = (key: string) => {
    const value = input[key];
    return typeof value === 'string' ? value : null;
  };
  const first =
    text('command') ?? text('path') ?? text('url') ?? text('query') ?? text('plan')?.split('\n')[0] ?? null;
  if (first) return first;
  if (text('ticket_id')) return [text('ticket_id'), text('status')].filter(Boolean).join(' 改为 ');
  return JSON.stringify(input);
}
