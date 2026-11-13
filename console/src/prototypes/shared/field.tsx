import type { ReactNode } from 'react';

import { InfoIcon } from '@/components/ui/glyphs';
import { Icon } from '@/components/ui/icon';
import { Label } from '@/components/ui/label';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import { cn } from '@/lib/cn';

export interface FieldProps {
  /** 控件的 id；错误文字的 id 是 `${id}-error`，控件用 `aria-describedby` 关联它。 */
  id: string;
  label: string;
  /** 补充说明放在标签旁 info 图标的提示里，不写成小字。 */
  hint?: string;
  /** 校验错误：就近显示在控件下方。 */
  error?: string | null;
  /** 控件不是单个可标注的元素（复选框组、规则表）时，标签用 `span`，控件自己给出可访问名称。 */
  group?: boolean;
  className?: string;
  children: ReactNode;
}

/** 控件的 `aria-invalid` 与 `aria-describedby`，与 Field 的错误文字对应。 */
export function fieldAria(id: string, error?: string | null) {
  return error ? { 'aria-invalid': true as const, 'aria-describedby': `${id}-error` } : {};
}

/**
 * 原型表单的一个字段：标签在上、控件、错误在下（13px 信号红）。T21 换成 React Hook Form 的字段封装，外观不变。
 */
export function Field({ id, label, hint, error, group = false, className, children }: FieldProps) {
  return (
    <div className={cn('flex min-w-0 flex-col gap-2', className)}>
      <div className="flex items-center gap-1">
        {group ? (
          <span id={`${id}-label`} className="text-13 leading-5 font-medium">
            {label}
          </span>
        ) : (
          <Label htmlFor={id}>{label}</Label>
        )}
        {hint && (
          <Tooltip>
            <TooltipTrigger asChild>
              <button
                type="button"
                aria-label={`${label}的说明`}
                className="inline-flex rounded-control text-ink-2 hover:text-ink"
              >
                <Icon glyph={InfoIcon} />
              </button>
            </TooltipTrigger>
            <TooltipContent>{hint}</TooltipContent>
          </Tooltip>
        )}
      </div>
      {children}
      {error && (
        <p id={`${id}-error`} className="text-12 text-danger">
          {error}
        </p>
      )}
    </div>
  );
}
