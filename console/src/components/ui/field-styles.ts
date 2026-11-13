/**
 * 输入类控件（输入框、文本域、选择器的触发器）共用的外观：控件边界 rule-strong（WCAG 1.4.11 的 3:1）、4px 圆角、13px 文字。
 * 焦点环由全局 :focus-visible 给出（2px 实线海图蓝）；只读与禁用在视觉上区分：只读保留墨色文字，禁用文字变灰。
 */
export const controlSurface = [
  'w-full min-w-0 rounded-control border border-rule-strong bg-surface text-13 text-ink',
  'transition-colors duration-150 ease-out placeholder:text-ink-2 hover:border-ink-2',
  'aria-invalid:border-danger aria-invalid:hover:border-danger',
  'read-only:border-rule read-only:bg-surface-inset read-only:hover:border-rule',
  'disabled:cursor-not-allowed disabled:border-rule disabled:bg-surface-inset disabled:text-ink-disabled disabled:placeholder:text-ink-disabled disabled:hover:border-rule',
].join(' ');

/** 单行控件的高度：md 32px 与按钮同高，sm 28px 用于筛选栏与表格内。 */
export const controlHeight = { sm: 'h-7 px-2', md: 'h-8 px-2' } as const;

export type ControlSize = keyof typeof controlHeight;
