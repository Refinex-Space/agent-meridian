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

/** 一级导航（架构设计「控制台·信息架构」的十一项）；`to` 是路由路径，每项一个路由模块（`app/router.tsx`）。 */
export const NAV = [
  { key: 'overview', label: '概览', glyph: GaugeIcon, to: '/projects/$projectId/overview' },
  { key: 'sessions', label: '会话', glyph: ThreadGlyph, to: '/projects/$projectId/sessions' },
  { key: 'playground', label: 'Playground', glyph: FlaskIcon, to: '/projects/$projectId/playground' },
  { key: 'agents', label: '智能体', glyph: AgentGlyph, to: '/projects/$projectId/agents' },
  { key: 'environments', label: '环境', glyph: SandboxGlyph, to: '/projects/$projectId/environments' },
  { key: 'capabilities', label: '能力', glyph: SkillGlyph, to: '/projects/$projectId/capabilities' },
  { key: 'knowledge', label: '知识库', glyph: KnowledgeGlyph, to: '/projects/$projectId/knowledge' },
  { key: 'models', label: '模型', glyph: CpuIcon, to: '/projects/$projectId/models' },
  { key: 'pending', label: '待处理', glyph: PendingActionGlyph, to: '/projects/$projectId/pending' },
  { key: 'usage', label: '用量与预算', glyph: CoinsIcon, to: '/projects/$projectId/usage' },
  { key: 'settings', label: '设置', glyph: GearSixIcon, to: '/projects/$projectId/settings' },
] as const satisfies readonly {
  key: string;
  label: string;
  glyph: Glyph;
  to: `/projects/$projectId/${string}`;
}[];

export type NavKey = (typeof NAV)[number]['key'];
export type NavItem = (typeof NAV)[number];
export type NavPath = NavItem['to'];
