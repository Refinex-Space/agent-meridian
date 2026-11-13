import type { ComponentType } from 'react';

import type { GlyphProps } from '@/assets/icons';
import { cn } from '@/lib/cn';

/** 图标只有三档尺寸（frontend-design.md「图标」）。 */
export type IconSize = 16 | 20 | 24;

/** 可传给 Icon 的图形：`glyphs.ts` 中登记的 Phosphor 图标或 `@/assets/icons` 的自研图标。 */
export type Glyph = ComponentType<GlyphProps>;

export interface IconProps {
  glyph: Glyph;
  size?: IconSize;
  /** regular 为默认，bold 用于当前项与强调；两种图标库的同名字重线宽一致。 */
  weight?: 'regular' | 'bold';
  /** 图标单独表达含义时（例如只有图标的按钮之外的状态图标）给出可访问名称；否则视为装饰，对读屏隐藏。 */
  label?: string;
  className?: string;
}

/**
 * 全控制台唯一的图标入口：统一尺寸档位与 `currentColor`，颜色由父元素的文字颜色决定。
 */
export function Icon({ glyph: GlyphComponent, size = 16, weight = 'regular', label, className }: IconProps) {
  const glyph = (
    <GlyphComponent size={size} weight={weight} className={cn('shrink-0', className)} aria-hidden />
  );
  if (!label) return glyph;
  return (
    <span role="img" aria-label={label} className="inline-flex">
      {glyph}
    </span>
  );
}
