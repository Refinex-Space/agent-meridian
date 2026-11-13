import {
  AgentGlyph,
  CoinsIcon,
  CpuIcon,
  FlaskIcon,
  GaugeIcon,
  GearSixIcon,
  KnowledgeGlyph,
  PendingActionGlyph,
  SandboxGlyph,
  SkillGlyph,
  ThreadGlyph,
} from '@/components/ui/glyphs';
import type { Glyph } from '@/components/ui/icon';

/** 一级导航（架构设计「控制台·信息架构」的十一项）。 */
export const NAV = [
  { key: 'overview', label: '概览', glyph: GaugeIcon, href: '/overview' },
  { key: 'sessions', label: '会话', glyph: ThreadGlyph, href: '/sessions' },
  { key: 'playground', label: 'Playground', glyph: FlaskIcon, href: '/playground' },
  { key: 'agents', label: '智能体', glyph: AgentGlyph, href: '/agents' },
  { key: 'environments', label: '环境', glyph: SandboxGlyph, href: '/environments' },
  { key: 'capabilities', label: '能力', glyph: SkillGlyph, href: '/capabilities' },
  { key: 'knowledge', label: '知识库', glyph: KnowledgeGlyph, href: '/knowledge' },
  { key: 'models', label: '模型', glyph: CpuIcon, href: '/models' },
  { key: 'pending', label: '待处理', glyph: PendingActionGlyph, href: '/pending' },
  { key: 'usage', label: '用量与预算', glyph: CoinsIcon, href: '/usage' },
  { key: 'settings', label: '设置', glyph: GearSixIcon, href: '/settings' },
] as const satisfies readonly { key: string; label: string; glyph: Glyph; href: string }[];

export type NavKey = (typeof NAV)[number]['key'];
