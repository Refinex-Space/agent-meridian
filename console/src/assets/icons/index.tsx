import type { ComponentType, SVGProps } from 'react';

import AgentSvg from './agent.svg?react';
import BrandSvg from './brand.svg?react';
import EmptyChartSvg from './empty-chart.svg?react';
import EmptyClearSvg from './empty-clear.svg?react';
import EmptySearchSvg from './empty-search.svg?react';
import KnowledgeSvg from './knowledge.svg?react';
import PendingActionSvg from './pending-action.svg?react';
import SandboxSvg from './sandbox.svg?react';
import SkillSvg from './skill.svg?react';
import ThreadSvg from './thread.svg?react';
import TurnSvg from './turn.svg?react';

/** 自研图标与 Phosphor 图标共用的属性：尺寸与字重。 */
export interface GlyphProps {
  size?: number;
  /** regular 为 256 网格上 16 的线宽，bold 为 24，与 Phosphor 的同名字重一致。 */
  weight?: 'regular' | 'bold';
  className?: string;
  'aria-hidden'?: boolean;
}

const STROKE = { regular: 16, bold: 24 } as const;

/** 把 SVGR 生成的组件包成与 Phosphor 相同的接口：`size` 给宽高，`weight` 换算为线宽。 */
function domainGlyph(Svg: ComponentType<SVGProps<SVGSVGElement>>, name: string): ComponentType<GlyphProps> {
  function Glyph({ size = 16, weight = 'regular', ...rest }: GlyphProps) {
    return <Svg width={size} height={size} strokeWidth={STROKE[weight]} focusable={false} {...rest} />;
  }
  Glyph.displayName = name;
  return Glyph;
}

/** 品牌标识：子午线弧、刻度与轴心的"此刻"菱形。 */
export const BrandMark = domainGlyph(BrandSvg, 'BrandMark');
/** 智能体。 */
export const AgentGlyph = domainGlyph(AgentSvg, 'AgentGlyph');
/** 线程（主线与子线程）。 */
export const ThreadGlyph = domainGlyph(ThreadSvg, 'ThreadGlyph');
/** 轮次。 */
export const TurnGlyph = domainGlyph(TurnSvg, 'TurnGlyph');
/** 沙箱。 */
export const SandboxGlyph = domainGlyph(SandboxSvg, 'SandboxGlyph');
/** 待处理动作。 */
export const PendingActionGlyph = domainGlyph(PendingActionSvg, 'PendingActionGlyph');
/** 技能。 */
export const SkillGlyph = domainGlyph(SkillSvg, 'SkillGlyph');
/** 知识库。 */
export const KnowledgeGlyph = domainGlyph(KnowledgeSvg, 'KnowledgeGlyph');

/** 空态插画的属性：只有一种尺寸（128×96），对读屏隐藏（空态的含义由文字表达）。 */
export interface IllustrationProps {
  className?: string;
}

/**
 * 空态插画：1024×768 的画布是 4×3 个 256 网格单元，线宽 16，与图标同一套网格与线宽；显示为 128×96 时线宽 2px。
 * 线条用 currentColor（由 EmptyState 给出刻线色），强调部分固定为海图蓝（`--palette-accent`，随 data-theme 解析）。
 */
function illustration(Svg: ComponentType<SVGProps<SVGSVGElement>>, name: string): ComponentType<IllustrationProps> {
  function Illustration({ className }: IllustrationProps) {
    return <Svg width={128} height={96} className={className} aria-hidden focusable={false} />;
  }
  Illustration.displayName = name;
  return Illustration;
}

/** 首次使用：一张空白的海图，纬线上一个待落笔的菱形。 */
export const EmptyChartIllustration = illustration(EmptyChartSvg, 'EmptyChartIllustration');
/** 筛选后为空：准星没有对准任何标记。 */
export const EmptySearchIllustration = illustration(EmptySearchSvg, 'EmptySearchIllustration');
/** 全部处理完：子午线走到终点，节点上一个对勾（例如没有待处理动作）。 */
export const EmptyClearIllustration = illustration(EmptyClearSvg, 'EmptyClearIllustration');
