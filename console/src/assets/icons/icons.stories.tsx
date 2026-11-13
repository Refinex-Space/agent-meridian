import type { Meta, StoryObj } from '@storybook/react-vite';

import {
  AgentGlyph,
  BrandMark,
  CubeIcon,
  DiamondIcon,
  GitBranchIcon,
  GlobeIcon,
  KnowledgeGlyph,
  PendingActionGlyph,
  RulerIcon,
  SandboxGlyph,
  SkillGlyph,
  StackIcon,
  ThreadGlyph,
  TriangleIcon,
  TurnGlyph,
  UserIcon,
} from '@/components/ui/glyphs';
import { Icon, type Glyph, type IconSize } from '@/components/ui/icon';

/** 每个自研图标与一个结构相近的 Phosphor 图标成对展示，比较线宽与视觉重量。 */
const PAIRS: { name: string; custom: Glyph; phosphor: Glyph; phosphorName: string }[] = [
  { name: '品牌标识', custom: BrandMark, phosphor: GlobeIcon, phosphorName: 'Globe' },
  { name: '智能体', custom: AgentGlyph, phosphor: UserIcon, phosphorName: 'User' },
  { name: '线程', custom: ThreadGlyph, phosphor: GitBranchIcon, phosphorName: 'GitBranch' },
  { name: '轮次', custom: TurnGlyph, phosphor: RulerIcon, phosphorName: 'Ruler' },
  { name: '沙箱', custom: SandboxGlyph, phosphor: CubeIcon, phosphorName: 'Cube' },
  { name: '待处理动作', custom: PendingActionGlyph, phosphor: DiamondIcon, phosphorName: 'Diamond' },
  { name: '技能', custom: SkillGlyph, phosphor: TriangleIcon, phosphorName: 'Triangle' },
  { name: '知识库', custom: KnowledgeGlyph, phosphor: StackIcon, phosphorName: 'Stack' },
];

const SIZES: IconSize[] = [16, 20, 24];

function IconsPage() {
  return (
    <div className="flex min-h-screen flex-col gap-12 bg-canvas p-8 text-ink">
      <h1 className="text-22 leading-heading font-semibold">图标</h1>

      <section className="flex flex-col gap-4">
        <h2 className="text-18 leading-heading font-semibold">自研图标与 Phosphor 并排</h2>
        <table className="w-full max-w-5xl border-collapse text-13">
          <thead>
            <tr className="border-b border-rule text-left text-12 text-ink-2">
              <th className="py-2 pr-6 font-medium">图标</th>
              {SIZES.map((size) => (
                <th key={size} className="py-2 pr-6 font-medium tabular-nums">
                  {size}px
                </th>
              ))}
              <th className="py-2 pr-6 font-medium">bold</th>
              <th className="py-2 font-medium">对照</th>
            </tr>
          </thead>
          <tbody>
            {PAIRS.map(({ name, custom, phosphor, phosphorName }) => (
              <tr key={name} className="border-b border-rule">
                <td className="py-3 pr-6">{name}</td>
                {SIZES.map((size) => (
                  <td key={size} className="py-3 pr-6">
                    <span className="flex items-center gap-2">
                      <Icon glyph={custom} size={size} />
                      <Icon glyph={phosphor} size={size} />
                    </span>
                  </td>
                ))}
                <td className="py-3 pr-6">
                  <span className="flex items-center gap-2">
                    <Icon glyph={custom} size={20} weight="bold" />
                    <Icon glyph={phosphor} size={20} weight="bold" />
                  </span>
                </td>
                <td className="py-3 text-12 text-ink-2">{phosphorName}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>

      <section className="flex flex-col gap-4">
        <h2 className="text-18 leading-heading font-semibold">放大比较线宽（96px）</h2>
        <div className="flex flex-wrap gap-6">
          {PAIRS.map(({ name, custom: Custom, phosphor: Phosphor }) => (
            <div key={name} className="flex flex-col items-center gap-2">
              <span className="flex gap-2 rounded-panel border border-rule bg-surface p-3">
                <Custom size={96} aria-hidden />
                <Phosphor size={96} aria-hidden />
              </span>
              <span className="text-12 text-ink-2">{name}</span>
            </div>
          ))}
        </div>
      </section>
    </div>
  );
}

const meta = {
  title: '基础/图标',
  component: IconsPage,
} satisfies Meta<typeof IconsPage>;

export default meta;

export const Icons: StoryObj<typeof meta> = { name: '图标' };
