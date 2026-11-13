/// <reference types="node" />
import { readFileSync } from 'node:fs';
import path from 'node:path';

import { describe, expect, it } from 'vitest';

import { contrastRatio, parseOklch, toRgb, type Rgb } from '@/lib/color';
import { COLOR_TOKENS, CONTRAST_REQUIREMENTS, type ColorToken } from '@/lib/tokens';

// Vitest 对样式表（含 `?raw`）一律返回空串，所以直接读文件；jsdom 环境下 import.meta.url 不是 file: 地址，用 dirname。
const css = readFileSync(path.join(import.meta.dirname, 'tokens.css'), 'utf8');

type Theme = 'light' | 'dark';

/** 一个调色板规则块（`:root, [data-theme='light']` 或 `[data-theme='dark']`）中的 `--palette-*` 值。 */
function readPalette(source: string, selector: RegExp): Map<string, Rgb> {
  const block = selector.exec(source)?.[1];
  if (!block) throw new Error(`tokens.css 缺少调色板 ${selector.source}`);
  const values = new Map<string, Rgb>();
  for (const [, name, value] of block.matchAll(/--palette-([a-z0-9-]+):\s*(oklch\([^)]*\));/g)) {
    const rgb = value ? toRgb(value) : null;
    if (!name || !rgb) throw new Error(`无法解析 --palette-${name}`);
    values.set(name, rgb);
  }
  return values;
}

/** 从 tokens.css 读出每个颜色令牌在亮暗两套调色板中的值；令牌必须以 `var(--palette-<同名>)` 映射。 */
function readColorTokens(source: string): Map<string, Record<Theme, Rgb>> {
  const light = readPalette(source, /:root,\s*\[data-theme='light'\]\s*\{([^}]*)\}/);
  const dark = readPalette(source, /\[data-theme='dark'\]\s*\{([^}]*)\}/);
  const tokens = new Map<string, Record<Theme, Rgb>>();
  for (const [, name, target] of source.matchAll(/--color-([a-z0-9-]+):\s*var\(--palette-([a-z0-9-]+)\);/g)) {
    if (!name || name !== target) throw new Error(`--color-${name} 应映射到 --palette-${name}`);
    const l = light.get(name);
    const d = dark.get(name);
    if (l && d) tokens.set(name, { light: l, dark: d });
  }
  return tokens;
}

const tokens = readColorTokens(css);

function color(name: ColorToken, theme: Theme): Rgb {
  const value = tokens.get(name);
  if (!value) throw new Error(`tokens.css 缺少令牌 --color-${name}`);
  return value[theme];
}

describe('颜色令牌', () => {
  it('清单中的每个令牌都映射到调色板，并在亮暗两套调色板中都有 OKLCH 值', () => {
    const missing = COLOR_TOKENS.map((t) => t.name).filter((name) => !tokens.has(name));
    expect(missing).toEqual([]);
  });

  it('tokens.css 中没有清单之外的颜色令牌', () => {
    const declared = [...css.matchAll(/--color-([a-z0-9-]+):/g)].map((m) => m[1]);
    const known = new Set<string>(COLOR_TOKENS.map((t) => t.name));
    expect(declared.filter((name) => name !== undefined && !known.has(name))).toEqual([]);
  });

  it.each(['light', 'dark'] as const)('%s 主题下每一对前景与背景都满足对比度下限', (theme) => {
    const failures = CONTRAST_REQUIREMENTS.map((req) => ({
      ...req,
      ratio: contrastRatio(color(req.fg, theme), color(req.bg, theme)),
    }))
      .filter((r) => r.ratio < r.min)
      .map((r) => `${r.fg} on ${r.bg}: ${r.ratio.toFixed(2)} < ${r.min}`);
    expect(failures).toEqual([]);
  });

  it('OKLCH 换算与设计定稿中的十六进制对照一致', () => {
    // 抽查三个：海图白、墨、海图蓝（亮色）。
    expect(toRgb('oklch(0.974 0.003 165)')?.map((v) => Math.round(v * 255))).toEqual([0xf5, 0xf7, 0xf6]);
    expect(toRgb('oklch(0.25 0.015 166)')?.map((v) => Math.round(v * 255))).toEqual([0x1b, 0x24, 0x20]);
    expect(toRgb('oklch(0.47 0.125 252)')?.map((v) => Math.round(v * 255))).toEqual([0x18, 0x5c, 0x9e]);
    expect(parseOklch('oklch(47% 0.125 252)')).toEqual({ l: 0.47, c: 0.125, h: 252 });
  });
});
