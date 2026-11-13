import type { ComponentType, SVGProps } from 'react';

import AgentSvg from './agent.svg?react';
import BrandSvg from './brand.svg?react';
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
