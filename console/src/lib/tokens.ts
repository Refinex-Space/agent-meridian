/**
 * 颜色令牌的清单与对比度要求。令牌的值只在 `src/styles/tokens.css`；这里只登记名字、用途与必须满足的对比度，
 * 令牌页按它渲染，`tokens.test.ts` 按它从 tokens.css 读出两套值逐对校验。
 */

export const COLOR_TOKENS = [
  { name: 'canvas', label: '海图白', usage: '应用背景' },
  { name: 'surface', label: '纸面', usage: '内容区、面板、表格' },
  { name: 'surface-raised', label: '浮层', usage: '下拉、提示、对话框' },
  { name: 'surface-inset', label: '内嵌', usage: '代码、JSON、时间栏' },
  { name: 'surface-hover', label: '悬停', usage: '行与控件的悬停底色' },
  { name: 'rule', label: '刻线', usage: '分隔线、面板边框' },
  { name: 'rule-strong', label: '控件边界', usage: '输入框、复选框的边界' },
  { name: 'ink', label: '墨', usage: '正文与标题' },
  { name: 'ink-2', label: '石墨', usage: '次要文字、图标' },
  { name: 'ink-disabled', label: '禁用', usage: '禁用态文字' },
  { name: 'accent', label: '海图蓝', usage: '主操作、链接、焦点、运行中' },
  { name: 'accent-hover', label: '海图蓝悬停', usage: '主按钮悬停' },
  { name: 'accent-weak', label: '海图蓝浅底', usage: '选中行、选中项' },
  { name: 'on-accent', label: '主按钮文字', usage: '海图蓝底上的文字' },
  { name: 'brass', label: '黄铜', usage: '"此刻"标记、等待处理的符号' },
  { name: 'brass-ink', label: '黄铜文字', usage: '等待处理的文字' },
  { name: 'brass-weak', label: '黄铜浅底', usage: '等待处理的行' },
  { name: 'success', label: '海绿', usage: '成功、健康' },
  { name: 'danger', label: '信号红', usage: '失败、拒绝、删除类操作' },
  { name: 'danger-hover', label: '信号红悬停', usage: '危险按钮悬停' },
  { name: 'danger-weak', label: '信号红浅底', usage: '错误行、错误提示' },
  { name: 'reschedule', label: '重新调度', usage: '重新调度的符号' },
  { name: 'terminated', label: '已终止', usage: '已终止的符号' },
  { name: 'meridian', label: '子午线', usage: '时间轴主线与刻度' },
  { name: 'lane', label: '子线程线', usage: '时间轴的子线程' },
  { name: 'scrim', label: '遮罩', usage: '对话框、抽屉下的压暗层' },
  { name: 'code-key', label: '语法：键', usage: 'JSON 与 YAML 的键、关键字' },
  { name: 'code-string', label: '语法：字符串', usage: '字符串' },
  { name: 'code-number', label: '语法：常量', usage: '数字、布尔值、null' },
  { name: 'code-function', label: '语法：函数', usage: '函数名、命令' },
] as const;

export type ColorToken = (typeof COLOR_TOKENS)[number]['name'];

/** 一对前景与背景必须满足的最低对比度：文字 4.5:1，图形与控件边界 3:1（WCAG 2.2 AA）。 */
export interface ContrastRequirement {
  fg: ColorToken;
  bg: ColorToken;
  min: 4.5 | 3;
  kind: 'text' | 'graphic';
}

function pairs(fg: ColorToken, bgs: ColorToken[], min: 4.5 | 3): ContrastRequirement[] {
  return bgs.map((bg) => ({ fg, bg, min, kind: min === 4.5 ? 'text' : 'graphic' }));
}

const SURFACES: ColorToken[] = ['canvas', 'surface', 'surface-raised', 'surface-inset', 'surface-hover'];

export const CONTRAST_REQUIREMENTS: readonly ContrastRequirement[] = [
  ...pairs('ink', [...SURFACES, 'accent-weak', 'brass-weak', 'danger-weak'], 4.5),
  ...pairs('ink-2', [...SURFACES, 'accent-weak'], 4.5),
  ...pairs('accent', ['canvas', 'surface', 'surface-raised', 'surface-inset', 'accent-weak'], 4.5),
  ...pairs('on-accent', ['accent', 'accent-hover', 'danger', 'danger-hover'], 4.5),
  ...pairs('brass-ink', ['canvas', 'surface', 'brass-weak'], 4.5),
  ...pairs('success', ['canvas', 'surface'], 4.5),
  ...pairs('danger', ['canvas', 'surface', 'danger-weak'], 4.5),
  ...pairs('brass', ['canvas', 'surface', 'surface-inset', 'brass-weak'], 3),
  ...pairs('reschedule', ['canvas', 'surface'], 3),
  ...pairs('terminated', ['canvas', 'surface'], 3),
  ...pairs('rule-strong', ['canvas', 'surface'], 3),
  ...pairs('meridian', ['canvas', 'surface', 'surface-inset', 'accent-weak', 'brass-weak'], 3),
  ...pairs('lane', ['surface', 'accent-weak'], 3),
  ...pairs('code-key', ['surface', 'surface-inset'], 4.5),
  ...pairs('code-string', ['surface', 'surface-inset'], 4.5),
  ...pairs('code-number', ['surface', 'surface-inset'], 4.5),
  ...pairs('code-function', ['surface', 'surface-inset'], 4.5),
];
