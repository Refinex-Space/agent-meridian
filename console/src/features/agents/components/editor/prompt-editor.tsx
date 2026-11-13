import Editor from '@monaco-editor/react';
import { useEffect, useRef, useState } from 'react';

import { Skeleton } from '@/components/ui/skeleton';
import { cn } from '@/lib/cn';

import { defineTheme } from './monaco';

export interface PromptEditorProps {
  /** 容器的 id（字段锚点，出错时聚焦到编辑区）。 */
  id: string;
  value: string;
  onChange: (value: string) => void;
  onBlur?: () => void;
  /** 读屏名称（"系统提示词"）。 */
  label: string;
  readOnly?: boolean;
  invalid?: boolean;
  describedBy?: string;
  /** 编辑区高度（像素）。 */
  height?: number;
  placeholder?: string;
}

/** 最近的 `data-theme`（应用写在 `<html>` 上）。 */
function currentTheme(el: Element): 'light' | 'dark' {
  return el.closest('[data-theme]')?.getAttribute('data-theme') === 'dark' ? 'dark' : 'light';
}

/**
 * 系统提示词的编辑器（Monaco，Markdown 语法）：随整个编辑页懒加载；主题从令牌生成，跟随亮暗切换；边框与输入框同一外观。
 */
export default function PromptEditor({
  id,
  value,
  onChange,
  onBlur,
  label,
  readOnly = false,
  invalid = false,
  describedBy,
  height = 360,
  placeholder,
}: PromptEditorProps) {
  const ref = useRef<HTMLDivElement>(null);
  const [theme, setTheme] = useState<string | null>(null);

  useEffect(() => {
    const el = ref.current;
    if (!el) return undefined;
    const apply = () => setTheme(defineTheme(el, currentTheme(el)));
    apply();
    const observer = new MutationObserver(apply);
    observer.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] });
    return () => observer.disconnect();
  }, []);

  return (
    <div
      id={id}
      ref={ref}
      aria-describedby={describedBy}
      className={cn(
        'overflow-hidden rounded-control border border-rule-strong bg-surface py-2 transition-colors duration-150',
        'focus-within:border-accent hover:border-ink-2',
        invalid && 'border-danger hover:border-danger',
        readOnly && 'border-rule bg-surface-inset hover:border-rule',
      )}
      style={{ height }}
    >
      {theme && (
        <Editor
          language="markdown"
          theme={theme}
          value={value}
          onChange={(next) => onChange(next ?? '')}
          onMount={(editor) => {
            editor.onDidBlurEditorText(() => onBlur?.());
          }}
          loading={<Skeleton className="mx-3 h-4 w-48" />}
          options={{
            ariaLabel: label,
            readOnly,
            placeholder,
            minimap: { enabled: false },
            wordWrap: 'on',
            lineNumbers: 'on',
            lineNumbersMinChars: 3,
            folding: false,
            glyphMargin: false,
            renderLineHighlight: 'line',
            scrollBeyondLastLine: false,
            overviewRulerLanes: 0,
            hideCursorInOverviewRuler: true,
            fontFamily:
              getComputedStyle(document.documentElement).getPropertyValue('--font-mono') || 'monospace',
            fontSize: 13,
            lineHeight: 21,
            tabSize: 2,
            padding: { top: 0, bottom: 0 },
            scrollbar: { verticalScrollbarSize: 10, horizontalScrollbarSize: 10, useShadows: false },
            automaticLayout: true,
            unicodeHighlight: { ambiguousCharacters: false, invisibleCharacters: false },
          }}
        />
      )}
    </div>
  );
}
