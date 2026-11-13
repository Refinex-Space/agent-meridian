import { clsx, type ClassValue } from 'clsx';
import { extendTailwindMerge } from 'tailwind-merge';

/**
 * 令牌的名字登记给 tailwind-merge：默认配置不认识 `text-12`（会当成文字颜色，与 `text-ink` 互相覆盖）、
 * `rounded-control`、`shadow-overlay` 这些自定义值。与 tokens.css 的 `@theme` 保持一致。
 */
const twMerge = extendTailwindMerge({
  extend: {
    theme: {
      color: [
        'canvas',
        'surface',
        'surface-raised',
        'surface-inset',
        'surface-hover',
        'rule',
        'rule-strong',
        'ink',
        'ink-2',
        'ink-disabled',
        'accent',
        'accent-hover',
        'accent-weak',
        'on-accent',
        'brass',
        'brass-ink',
        'brass-weak',
        'success',
        'danger',
        'danger-hover',
        'danger-weak',
        'reschedule',
        'terminated',
        'meridian',
        'lane',
        'scrim',
        'code-key',
        'code-string',
        'code-number',
        'code-function',
      ],
      text: ['12', '13', '14', '16', '18', '22', '28'],
      leading: ['heading', 'body', 'table'],
      radius: ['control', 'panel', 'overlay'],
      shadow: ['overlay'],
      ease: ['out', 'in'],
      animate: [
        'breath',
        'skeleton',
        'arrive',
        'fade-in-slow',
        'overlay-in',
        'overlay-out',
        'fade-in',
        'fade-out',
        'dialog-in',
        'sheet-in-right',
        'sheet-out-right',
        'sheet-in-left',
        'sheet-out-left',
        'spin',
      ],
    },
  },
});

/** 合并类名：条件拼接（clsx）后按 Tailwind 语义去重，后者覆盖前者。 */
export function cn(...inputs: ClassValue[]): string {
  return twMerge(clsx(inputs));
}
