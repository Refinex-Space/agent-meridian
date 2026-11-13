import type { ReactNode } from 'react';

import { InfoIcon } from '@/components/ui/glyphs';
import { Icon } from '@/components/ui/icon';
import { Label } from '@/components/ui/label';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import { cn } from '@/lib/cn';

export interface FormFieldProps {
  /** 控件的 id；错误文字的 id 是 `${id}-error`，控件用 `fieldAria(id, error)` 关联它。 */
  id: string;
  label: string;
  /** 补充说明放在标签旁 info 图标的提示里，不写成小字。 */
  hint?: string;
  /** 校验错误：就近显示在控件下方。 */
  error?: string | null | undefined;
  /** 控件不是单个可标注的元素（复选框组、规则表）时，标签用 `span`（id 是 `${id}-label`），控件经 aria-labelledby 引用它。 */
  group?: boolean;
  /** 标签右侧的附加内容（例如"全部清除"的文字按钮）。 */
  aside?: ReactNode;
  className?: string;
  children: ReactNode;
}

/** 控件的 `aria-invalid` 与 `aria-describedby`，与 FormField 的错误文字对应。 */
export function fieldAria(id: string, error?: string | null) {
  return error ? { 'aria-invalid': true as const, 'aria-describedby': `${id}-error` } : {};
}

/**
 * 表单的一个字段：标签在上（13px、500 字重），控件，错误在下（12px 信号红）。补充说明只放在 info 提示里；
 * 状态由调用方（React Hook Form 的值与校验结果）给出，组件本身不持有状态。
 */
export function FormField({
  id,
  label,
  hint,
  error,
  group = false,
  aside,
  className,
  children,
}: FormFieldProps) {
  return (
    <div className={cn('flex min-w-0 flex-col gap-2', className)}>
      <div className="flex min-h-5 items-center gap-1">
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
            <TooltipContent className="max-w-80">{hint}</TooltipContent>
          </Tooltip>
        )}
        {aside && <div className="ml-auto flex items-center">{aside}</div>}
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
