/**
 * Monaco 的装配（只在提示词编辑器的懒加载块里引入）：编辑器核心、提示词编辑用得到的几项功能（查找、剪贴板、右键菜单、多光标、
 * 行操作、注释、括号匹配、占位文字）与 Markdown 语法，不打包其他语言与语言服务；worker 是本地构建的 `editor.worker`，
 * 不从 CDN 加载（离线环境同样可用）。`@monaco-editor/react` 经 `loader.config({ monaco })` 用这里的实例。
 */
import { loader } from '@monaco-editor/react';
import * as monaco from 'monaco-editor/editor/editor.api';
// worker 的入口没有类型声明；Vite 的 `?worker` 把它构建成本地的 Worker 构造器（vite/client 的类型）。
import EditorWorker from 'monaco-editor/editor/editor.worker?worker';
import 'monaco-editor/features/bracketMatching/register';
import 'monaco-editor/features/clipboard/register';
import 'monaco-editor/features/codicon/register';
import 'monaco-editor/features/comment/register';
import 'monaco-editor/features/contextmenu/register';
import 'monaco-editor/features/cursorUndo/register';
import 'monaco-editor/features/find/register';
import 'monaco-editor/features/linesOperations/register';
import 'monaco-editor/features/multicursor/register';
import 'monaco-editor/features/placeholderText/register';
import 'monaco-editor/languages/definitions/markdown/register';

import { toHex, toRgb } from '@/lib/color';

self.MonacoEnvironment = { getWorker: () => new EditorWorker() };
loader.config({ monaco });

/** 从令牌读出颜色（Monaco 只认十六进制）。 */
function palette(el: Element) {
  const style = getComputedStyle(el);
  return (name: string) => toHex(toRgb(style.getPropertyValue(`--palette-${name}`).trim()) ?? [0, 0, 0]);
}

/**
 * 按当前令牌定义编辑器主题（亮暗各一套，名字带主题）：底色是 `surface`，正文墨色，行号石墨色，选区海图蓝浅底，
 * Markdown 的标题、代码、链接用只读代码的四种语法色。主题切换时重新定义。
 */
export function defineTheme(el: Element, theme: 'light' | 'dark'): string {
  const c = palette(el);
  const name = `meridian-${theme}`;
  const hex = (token: string) => c(token).replace('#', '');
  monaco.editor.defineTheme(name, {
    base: theme === 'dark' ? 'vs-dark' : 'vs',
    inherit: true,
    rules: [
      { token: '', foreground: hex('ink') },
      { token: 'keyword', foreground: hex('code-key'), fontStyle: 'bold' },
      { token: 'strong', fontStyle: 'bold' },
      { token: 'emphasis', fontStyle: 'italic' },
      { token: 'string', foreground: hex('code-string') },
      { token: 'variable', foreground: hex('code-function') },
      { token: 'comment', foreground: hex('ink-2') },
    ],
    colors: {
      'editor.background': c('surface'),
      'editor.foreground': c('ink'),
      'editorLineNumber.foreground': c('ink-2'),
      'editorLineNumber.activeForeground': c('ink'),
      'editor.lineHighlightBackground': c('surface-hover'),
      'editor.lineHighlightBorder': c('surface-hover'),
      'editor.selectionBackground': c('accent-weak'),
      'editor.inactiveSelectionBackground': c('accent-weak'),
      'editorCursor.foreground': c('accent'),
      'editorWidget.background': c('surface-raised'),
      'editorWidget.border': c('rule'),
      'input.background': c('surface'),
      'input.border': c('rule-strong'),
      focusBorder: c('accent'),
      'editorGutter.background': c('surface'),
      'scrollbarSlider.background': `${c('rule-strong')}66`,
      'scrollbarSlider.hoverBackground': `${c('rule-strong')}99`,
      'editorWhitespace.foreground': c('rule'),
    },
  });
  return name;
}
