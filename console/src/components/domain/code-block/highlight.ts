/**
 * CodeBlock 的语法高亮：Shiki 的核心加 JavaScript 正则引擎（不加载 WASM），只引入控制台用得到的五种语法。
 * 这个模块由 CodeBlock 动态导入，Shiki 不进首屏包。
 *
 * 主题的颜色写成 `var(--palette-*)`：Shiki 把它原样放进每个 token 的颜色，在使用处按最近的 data-theme 解析，
 * 亮暗两套由 tokens.css 给出（语法色令牌经对比度测试）。不用 Shiki 自带的主题，它们都是十六进制颜色。
 */
import {
  createHighlighterCore,
  type HighlighterCore,
  type ThemedToken,
  type ThemeRegistrationRaw,
} from 'shiki/core';
import { createJavaScriptRegexEngine } from 'shiki/engine/javascript';

export type HighlightLanguage = 'json' | 'shell' | 'python' | 'yaml' | 'sql';

const GRAMMAR: Record<HighlightLanguage, string> = {
  json: 'json',
  shell: 'shellscript',
  python: 'python',
  yaml: 'yaml',
  sql: 'sql',
};

const INK = 'var(--palette-ink)';
const MUTED = 'var(--palette-ink-2)';

const theme: ThemeRegistrationRaw = {
  name: 'meridian',
  type: 'light',
  colors: { 'editor.foreground': INK, 'editor.background': 'var(--palette-surface-inset)' },
  settings: [
    { settings: { foreground: INK } },
    { scope: ['comment', 'punctuation.definition.comment'], settings: { foreground: MUTED } },
    { scope: ['punctuation', 'meta.brace', 'keyword.operator'], settings: { foreground: MUTED } },
    {
      scope: ['string', 'string.quoted', 'string.template'],
      settings: { foreground: 'var(--palette-code-string)' },
    },
    {
      scope: ['constant.numeric', 'constant.language', 'constant.character', 'constant.other'],
      settings: { foreground: 'var(--palette-code-number)' },
    },
    {
      scope: ['support.type.property-name', 'entity.name.tag', 'keyword', 'storage'],
      settings: { foreground: 'var(--palette-code-key)' },
    },
    {
      scope: ['entity.name.function', 'support.function', 'entity.name.command'],
      settings: { foreground: 'var(--palette-code-function)' },
    },
  ],
};

let highlighter: Promise<HighlighterCore> | null = null;

function getHighlighter(): Promise<HighlighterCore> {
  highlighter ??= createHighlighterCore({
    themes: [theme],
    langs: [
      import('shiki/langs/json.mjs'),
      import('shiki/langs/shellscript.mjs'),
      import('shiki/langs/python.mjs'),
      import('shiki/langs/yaml.mjs'),
      import('shiki/langs/sql.mjs'),
    ],
    engine: createJavaScriptRegexEngine(),
  });
  return highlighter;
}

/** 把代码切成带颜色的 token 行。 */
export async function highlight(code: string, language: HighlightLanguage): Promise<ThemedToken[][]> {
  const instance = await getHighlighter();
  return instance.codeToTokensBase(code, { lang: GRAMMAR[language], theme: 'meridian' });
}
