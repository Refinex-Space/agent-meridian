import type { Meta, StoryObj } from '@storybook/react-vite';
import { useLayoutEffect, useRef, useState, type CSSProperties, type ReactNode } from 'react';

import { Button } from '@/components/ui/button';
import { CheckIcon, XIcon } from '@/components/ui/glyphs';
import { Icon } from '@/components/ui/icon';
import { contrastRatio, toHex, toRgb, type Rgb } from '@/lib/color';
import { cn } from '@/lib/cn';
import { COLOR_TOKENS, CONTRAST_REQUIREMENTS, type ColorToken } from '@/lib/tokens';

type Scheme = 'light' | 'dark';
const SCHEMES: { scheme: Scheme; label: string }[] = [
  { scheme: 'light', label: '亮色' },
  { scheme: 'dark', label: '暗色' },
];

/** 令牌页按调色板变量取色：`--color-*` 在 :root 上已经解析，嵌套的 data-theme 只对 `--palette-*` 生效。 */
const tokenVar = (name: ColorToken) => `var(--palette-${name})`;

/**
 * 读出每个令牌在亮暗两套调色板下实际渲染的颜色：两个隐藏容器分别用 data-theme 固定主题，
 * 里面每个探针的 color 取一个令牌，getComputedStyle 给出解析后的值。
 */
function useRenderedColors() {
  const probes = useRef<HTMLDivElement>(null);
  const [colors, setColors] = useState<Record<Scheme, Partial<Record<ColorToken, Rgb>>>>({
    light: {},
    dark: {},
  });
  useLayoutEffect(() => {
    const root = probes.current;
    if (!root) return;
    const next: Record<Scheme, Partial<Record<ColorToken, Rgb>>> = { light: {}, dark: {} };
    root.querySelectorAll<HTMLElement>('[data-token]').forEach((el) => {
      const rgb = toRgb(getComputedStyle(el).color);
      const scheme = el.dataset['scheme'] as Scheme;
      const token = el.dataset['token'] as ColorToken;
      if (rgb) next[scheme][token] = rgb;
    });
    setColors(next);
  }, []);
  const probeElements = (
    <div ref={probes} aria-hidden className="hidden">
      {SCHEMES.map(({ scheme }) => (
        <div key={scheme} data-theme={scheme}>
          {COLOR_TOKENS.map(({ name }) => (
            <span key={name} data-token={name} data-scheme={scheme} style={{ color: tokenVar(name) }} />
          ))}
        </div>
      ))}
    </div>
  );
  return { colors, probeElements };
}

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="flex flex-col gap-4">
      <h2 className="text-18 leading-heading font-semibold">{title}</h2>
      {children}
    </section>
  );
}

function Swatch({ token, scheme, className }: { token: ColorToken; scheme: Scheme; className?: string }) {
  return (
    <span
      className={cn('block h-7 w-12 rounded-control border border-rule', className)}
      data-theme={scheme}
      style={{ background: tokenVar(token) }}
    />
  );
}

function Palette({ colors }: { colors: Record<Scheme, Partial<Record<ColorToken, Rgb>>> }) {
  return (
    <table className="w-full max-w-5xl border-collapse text-13 leading-table">
      <thead>
        <tr className="border-b border-rule text-left text-12 text-ink-2">
          <th className="py-2 pr-4 font-medium">令牌</th>
          <th className="py-2 pr-4 font-medium">用途</th>
          {SCHEMES.map(({ scheme, label }) => (
            <th key={scheme} className="py-2 pr-4 font-medium">
              {label}
            </th>
          ))}
        </tr>
      </thead>
      <tbody>
        {COLOR_TOKENS.map(({ name, label, usage }) => (
          <tr key={name} className="border-b border-rule">
            <td className="py-2 pr-4">
              <div className="font-medium">{label}</div>
              <code className="text-12 text-ink-2">{name}</code>
            </td>
            <td className="py-2 pr-4 text-ink-2">{usage}</td>
            {SCHEMES.map(({ scheme }) => {
              const rgb = colors[scheme][name];
              return (
                <td key={scheme} className="py-2 pr-4">
                  <div className="flex items-center gap-3">
                    <Swatch token={name} scheme={scheme} />
                    <code className="text-12 text-ink-2">{rgb ? toHex(rgb) : ''}</code>
                  </div>
                </td>
              );
            })}
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function ContrastSample({
  fg,
  bg,
  scheme,
  kind,
}: {
  fg: ColorToken;
  bg: ColorToken;
  scheme: Scheme;
  kind: 'text' | 'graphic';
}) {
  const style: CSSProperties = { background: tokenVar(bg), color: tokenVar(fg) };
  return (
    <span
      data-theme={scheme}
      className="flex h-8 w-28 items-center gap-2 rounded-control border border-rule px-2"
      style={style}
    >
      {kind === 'text' ? (
        <span className="text-14">正文 Aa 12</span>
      ) : (
        <svg viewBox="0 0 16 16" className="size-4" aria-hidden>
          <path d="M8 1.5 14.5 8 8 14.5 1.5 8z" fill="currentColor" />
        </svg>
      )}
    </span>
  );
}

function ContrastTable({ colors }: { colors: Record<Scheme, Partial<Record<ColorToken, Rgb>>> }) {
  return (
    <table className="w-full max-w-5xl border-collapse text-13 leading-table">
      <thead>
        <tr className="border-b border-rule text-left text-12 text-ink-2">
          <th className="py-2 pr-4 font-medium">前景</th>
          <th className="py-2 pr-4 font-medium">背景</th>
          <th className="py-2 pr-4 text-right font-medium">要求</th>
          {SCHEMES.map(({ scheme, label }) => (
            <th key={scheme} className="py-2 pr-4 font-medium" colSpan={2}>
              {label}
            </th>
          ))}
        </tr>
      </thead>
      <tbody>
        {CONTRAST_REQUIREMENTS.map(({ fg, bg, min, kind }) => (
          <tr key={`${fg}/${bg}`} className="border-b border-rule">
            <td className="py-2 pr-4">
              <code className="text-12">{fg}</code>
            </td>
            <td className="py-2 pr-4">
              <code className="text-12">{bg}</code>
            </td>
            <td className="py-2 pr-4 text-right text-ink-2">
              {kind === 'text' ? '文字' : '图形'} {min}:1
            </td>
            {SCHEMES.map(({ scheme }) => {
              const a = colors[scheme][fg];
              const b = colors[scheme][bg];
              const ratio = a && b ? contrastRatio(a, b) : null;
              const pass = ratio !== null && ratio >= min;
              return [
                <td key={`${scheme}-sample`} className="py-2 pr-2">
                  <ContrastSample fg={fg} bg={bg} scheme={scheme} kind={kind} />
                </td>,
                <td key={`${scheme}-ratio`} className="py-2 pr-6">
                  {ratio !== null && (
                    <span
                      className={cn(
                        'inline-flex items-center gap-1 tabular-nums',
                        pass ? 'text-success' : 'text-danger',
                      )}
                    >
                      <Icon glyph={pass ? CheckIcon : XIcon} label={pass ? '满足' : '不足'} />
                      {ratio.toFixed(2)}
                    </span>
                  )}
                </td>,
              ];
            })}
          </tr>
        ))}
      </tbody>
    </table>
  );
}

const TYPE_SCALE = [
  { cls: 'text-12', spec: '12 / 16', use: '时间刻度、表头' },
  { cls: 'text-13', spec: '13 / 20', use: '表格、按钮、次要信息' },
  { cls: 'text-14', spec: '14 / 21', use: '正文' },
  { cls: 'text-16', spec: '16 / 24', use: '区块标题' },
  { cls: 'text-18', spec: '18 / 24', use: '面板标题' },
  { cls: 'text-22', spec: '22 / 28', use: '页面标题' },
  { cls: 'text-28', spec: '28 / 36', use: '空态的主句' },
] as const;

function Typography() {
  return (
    <div className="flex flex-col gap-6">
      <table className="w-full max-w-5xl border-collapse">
        <tbody>
          {TYPE_SCALE.map(({ cls, spec, use }) => (
            <tr key={cls} className="border-b border-rule align-baseline">
              <td className="w-24 py-3 pr-4">
                <code className="text-12 text-ink-2">{cls}</code>
              </td>
              <td className="w-20 py-3 pr-4 text-12 text-ink-2 tabular-nums">{spec}</td>
              <td className={cn('py-3 pr-4', cls)}>经营分析助手完成了第 3 轮 Session 10:02:14</td>
              <td className="py-3 text-12 text-ink-2">{use}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <div className="grid max-w-5xl grid-cols-3 gap-6">
        {(
          [
            ['font-normal', '400 常规'],
            ['font-medium', '500 中等'],
            ['font-semibold', '600 半粗'],
          ] as const
        ).map(([cls, label]) => (
          <div key={cls} className="flex flex-col gap-1">
            <span className="text-12 text-ink-2">{label}</span>
            <span className={cn('text-16', cls)}>待处理动作 Pending 128</span>
          </div>
        ))}
      </div>
      <div className="grid max-w-5xl grid-cols-3 gap-6">
        <div className="flex flex-col gap-1">
          <span className="text-12 text-ink-2">IBM Plex Sans</span>
          <span className="text-16">Agent Meridian 0123456789</span>
        </div>
        <div className="flex flex-col gap-1">
          <span className="text-12 text-ink-2">IBM Plex Sans SC</span>
          <span className="text-16">子午线是测绘的基准线</span>
        </div>
        <div className="flex flex-col gap-1">
          <span className="text-12 text-ink-2">IBM Plex Mono（只给机器文本）</span>
          <code className="text-14">ses_01JC8Z4V7Q9X3M2N6P0R5T8W1Y</code>
        </div>
      </div>
    </div>
  );
}

const SPACING = [
  ['1', 'w-1'],
  ['2', 'w-2'],
  ['3', 'w-3'],
  ['4', 'w-4'],
  ['6', 'w-6'],
  ['8', 'w-8'],
  ['12', 'w-12'],
] as const;

function Spacing() {
  return (
    <div className="flex flex-col gap-2">
      {SPACING.map(([step, width]) => (
        <div key={step} className="flex items-center gap-4">
          <code className="w-8 text-12 text-ink-2">{step}</code>
          <span className="w-12 text-12 text-ink-2 tabular-nums">{Number(step) * 4}px</span>
          <span className={cn('h-3 bg-accent', width)} />
        </div>
      ))}
    </div>
  );
}

function Shapes() {
  return (
    <div className="flex flex-wrap items-start gap-8">
      {(
        [
          ['rounded-control', '4px 控件、徽标'],
          ['rounded-panel', '6px 面板、卡片'],
          ['rounded-overlay', '8px 浮层'],
        ] as const
      ).map(([cls, label]) => (
        <div key={cls} className="flex flex-col gap-2">
          <span className={cn('block size-24 border border-rule-strong bg-surface', cls)} />
          <code className="text-12">{cls}</code>
          <span className="text-12 text-ink-2">{label}</span>
        </div>
      ))}
      <div className="flex flex-col gap-2">
        <span className="shadow-overlay block size-24 rounded-overlay border border-rule bg-surface-raised" />
        <code className="text-12">shadow-overlay</code>
        <span className="text-12 text-ink-2">唯一的阴影，只给浮层</span>
      </div>
    </div>
  );
}

const DURATIONS = [
  ['duration-150', '150ms', '悬停、按下、提示'],
  ['duration-200', '200ms', '事件落位、面板展开'],
  ['duration-250', '250ms', '对话框、检查器收起'],
] as const;

function Motion() {
  const [moved, setMoved] = useState(false);
  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-col gap-3">
        {DURATIONS.map(([cls, label, use]) => (
          <div key={cls} className="flex items-center gap-4">
            <span className="w-14 text-12 text-ink-2 tabular-nums">{label}</span>
            <span className="relative block h-4 w-48 border-b border-rule">
              <span
                className={cn(
                  'absolute top-0 left-0 block size-3 rotate-45 bg-ink transition ease-out motion-reduce:transition-opacity',
                  cls,
                  moved ? 'translate-x-44 motion-reduce:translate-x-0 motion-reduce:opacity-40' : '',
                )}
              />
            </span>
            <span className="text-12 text-ink-2">{use}</span>
          </div>
        ))}
      </div>
      <div className="flex items-center gap-6">
        <Button size="sm" onClick={() => setMoved((v) => !v)}>
          {moved ? '复位' : '播放'}
        </Button>
        <span className="flex items-center gap-2 text-13 text-brass-ink">
          <span className="block size-3 rotate-45 bg-brass motion-safe:animate-breath" />
          此刻（2 秒呼吸，减少动效时静止）
        </span>
      </div>
    </div>
  );
}

function TokensPage() {
  const { colors, probeElements } = useRenderedColors();
  return (
    <div className="flex min-h-screen flex-col gap-12 bg-canvas p-8 text-ink">
      {probeElements}
      <h1 className="text-22 leading-heading font-semibold">设计令牌</h1>
      <Section title="色板">
        <Palette colors={colors} />
      </Section>
      <Section title="对比度">
        <ContrastTable colors={colors} />
      </Section>
      <Section title="字体与字号">
        <Typography />
      </Section>
      <Section title="间距">
        <Spacing />
      </Section>
      <Section title="圆角与阴影">
        <Shapes />
      </Section>
      <Section title="动效">
        <Motion />
      </Section>
    </div>
  );
}

const meta = {
  title: '基础/令牌',
  component: TokensPage,
} satisfies Meta<typeof TokensPage>;

export default meta;

export const Tokens: StoryObj<typeof meta> = { name: '令牌' };
