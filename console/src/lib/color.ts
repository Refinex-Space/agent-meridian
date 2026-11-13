/**
 * 颜色换算与 WCAG 对比度：OKLCH 到 sRGB（Björn Ottosson 的 OKLab 变换），相对亮度与对比度按 WCAG 2.2。
 * 令牌页用它显示对比度，令牌测试用它守住对比度下限。
 */

export interface Oklch {
  l: number;
  c: number;
  h: number;
}

export type Rgb = readonly [number, number, number];

const NUMBER = String.raw`[-+]?(?:\d+\.?\d*|\.\d+)`;
const OKLCH = new RegExp(
  String.raw`^oklch\(\s*(${NUMBER})(%?)\s+(${NUMBER})\s+(${NUMBER})(?:deg)?\s*(?:/\s*[^)]+)?\)$`,
  'i',
);
const RGB = new RegExp(String.raw`^rgba?\(\s*(${NUMBER})[\s,]+(${NUMBER})[\s,]+(${NUMBER})`, 'i');

/** 解析 `oklch(L C H)`（L 可为 0–1 或百分比）；不是 OKLCH 时返回 null。 */
export function parseOklch(value: string): Oklch | null {
  const match = OKLCH.exec(value.trim());
  if (!match) return null;
  const [, l, percent, c, h] = match;
  const lightness = Number(l) / (percent ? 100 : 1);
  return { l: lightness, c: Number(c), h: Number(h) };
}

function linearToSrgb(x: number): number {
  const v = Math.min(1, Math.max(0, x));
  return v <= 0.0031308 ? 12.92 * v : 1.055 * v ** (1 / 2.4) - 0.055;
}

function srgbToLinear(x: number): number {
  return x <= 0.04045 ? x / 12.92 : ((x + 0.055) / 1.055) ** 2.4;
}

/** OKLCH 到 sRGB（各分量 0–1，超出色域的分量截断）。 */
export function oklchToRgb({ l, c, h }: Oklch): Rgb {
  const a = c * Math.cos((h * Math.PI) / 180);
  const b = c * Math.sin((h * Math.PI) / 180);
  const l_ = (l + 0.3963377774 * a + 0.2158037573 * b) ** 3;
  const m_ = (l - 0.1055613458 * a - 0.0638541728 * b) ** 3;
  const s_ = (l - 0.0894841775 * a - 1.291485548 * b) ** 3;
  return [
    linearToSrgb(4.0767416621 * l_ - 3.3077115913 * m_ + 0.2309699292 * s_),
    linearToSrgb(-1.2684380046 * l_ + 2.6097574011 * m_ - 0.3413193965 * s_),
    linearToSrgb(-0.0041960863 * l_ - 0.7034186147 * m_ + 1.707614701 * s_),
  ];
}

/** 解析 `oklch(...)` 或 `rgb(...)` 为 sRGB；无法解析时返回 null。 */
export function toRgb(value: string): Rgb | null {
  const oklch = parseOklch(value);
  if (oklch) return oklchToRgb(oklch);
  const rgb = RGB.exec(value.trim());
  if (rgb) return [Number(rgb[1]) / 255, Number(rgb[2]) / 255, Number(rgb[3]) / 255];
  return null;
}

/** 六位大写十六进制，用于文档与令牌页的对照。 */
export function toHex(rgb: Rgb): string {
  return `#${rgb
    .map((v) =>
      Math.round(v * 255)
        .toString(16)
        .padStart(2, '0'),
    )
    .join('')
    .toUpperCase()}`;
}

/** WCAG 相对亮度。 */
export function relativeLuminance([r, g, b]: Rgb): number {
  return 0.2126 * srgbToLinear(r) + 0.7152 * srgbToLinear(g) + 0.0722 * srgbToLinear(b);
}

/** WCAG 对比度，1 至 21。 */
export function contrastRatio(a: Rgb, b: Rgb): number {
  const la = relativeLuminance(a);
  const lb = relativeLuminance(b);
  return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
}
