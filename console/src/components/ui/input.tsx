/*
 * 来源：shadcn/ui new-york-v4 `input`（shadcn CLI 4.23.0 生成，2026-11-13）。与上游的差异：
 * - 外观换成 field-styles 的 controlSurface：控件边界 rule-strong、4px 圆角、13px 文字，去掉 shadow-xs、dark: 分支与 3px ring 光晕；
 * - 新增 `size`（sm 28px、md 32px），md 与按钮同高；去掉上游 md 断点的字号切换（控制台只有桌面目标）；
 * - 只读与禁用分开表达（只读保留墨色文字），禁用不用整体半透明；
 * - 去掉 file: 前缀的样式（上传用专门的组件，不用原生文件输入框的外观）。
 */
import type { ComponentProps } from 'react';

import { cn } from '@/lib/cn';

import { controlHeight, controlSurface, type ControlSize } from './field-styles';

export interface InputProps extends Omit<ComponentProps<'input'>, 'size'> {
  size?: ControlSize;
}

/** 单行输入框。校验失败时设 `aria-invalid`，错误文字放在字段下方并用 `aria-describedby` 关联。 */
export function Input({ className, type = 'text', size = 'md', ...props }: InputProps) {
  return (
    <input
      type={type}
      data-slot="input"
      className={cn(controlSurface, controlHeight[size], className)}
      {...props}
    />
  );
}
