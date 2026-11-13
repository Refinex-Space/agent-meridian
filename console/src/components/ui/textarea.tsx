/*
 * 来源：shadcn/ui new-york-v4 `textarea`（shadcn CLI 4.23.0 生成，2026-11-13）。与上游的差异：
 * - 外观与输入框一致（field-styles 的 controlSurface），去掉 shadow-xs、dark: 分支与 3px ring 光晕；
 * - 行高 20px、内边距 8px，最小高度四行；保留 field-sizing-content（随内容增高），只允许竖向拉伸；
 * - 只读与禁用分开表达，禁用不用整体半透明。
 */
import type { ComponentProps } from 'react';

import { cn } from '@/lib/cn';

import { controlSurface } from './field-styles';

/** 多行文本域：提示词、说明这类较长的文字。 */
export function Textarea({ className, ...props }: ComponentProps<'textarea'>) {
  return (
    <textarea
      data-slot="textarea"
      className={cn(controlSurface, 'field-sizing-content min-h-24 resize-y px-2 py-2 leading-5', className)}
      {...props}
    />
  );
}
