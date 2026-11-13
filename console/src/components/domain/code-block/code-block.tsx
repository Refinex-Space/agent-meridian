import { useEffect, useState } from 'react';
import type { ThemedToken } from 'shiki/core';

import { Button } from '@/components/ui/button';
import { CheckIcon, CopyIcon, WarningCircleIcon } from '@/components/ui/glyphs';
import { Icon } from '@/components/ui/icon';
import { Skeleton } from '@/components/ui/skeleton';
import { cn } from '@/lib/cn';
import { useCopy } from '@/lib/use-copy';

import type { HighlightLanguage } from './highlight';

export interface CodeBlockProps {
  code: string;
  /** 语法；`text` 不高亮（日志、命令输出）。 */
  language?: HighlightLanguage | 'text';
  /** 内容的名称，组成复制按钮的可访问名称"复制{label}"，例如"入参""原始 JSON"。 */
  label: string;
  /** 超过这么多行时折叠，显示"展开全部"；0 表示不折叠。默认 16。 */
  collapseAfter?: number;
  /** 长行换行（默认）；false 时横向滚动。 */
  wrap?: boolean;
  /** 加载中：画 4 行骨架。 */
  loading?: boolean;
  className?: string;
}

/** 用 Shiki 高亮；模块与语法按需加载，加载完成前先显示纯文本，几何不变。 */
function useTokens(code: string, language: CodeBlockProps['language']) {
  const [result, setResult] = useState<{ code: string; tokens: ThemedToken[][] } | null>(null);
  useEffect(() => {
    if (language === undefined || language === 'text' || code === '') return undefined;
    let cancelled = false;
    void import('./highlight')
      .then(({ highlight }) => highlight(code, language))
      .then((tokens) => {
        if (!cancelled) setResult({ code, tokens });
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, [code, language]);
  return result?.code === code ? result.tokens : null;
}

/**
 * 只读的代码与 JSON：surface-inset 底、墨色等宽字（与页面同一明度层级，不放深色代码卡片），Shiki 高亮，一键复制，
 * 长内容折叠到合理高度、可展开。
 */
export function CodeBlock({
  code,
  language = 'text',
  label,
  collapseAfter = 16,
  wrap = true,
  loading = false,
  className,
}: CodeBlockProps) {
  const { state, copy } = useCopy();
  const [expanded, setExpanded] = useState(false);
  const tokens = useTokens(code, language);
  const lines = code === '' ? [] : code.split('\n');
  const collapsible = collapseAfter > 0 && lines.length > collapseAfter;
  const visible = collapsible && !expanded ? collapseAfter : lines.length;

  if (loading) {
    return (
      <div aria-busy className={cn('flex flex-col gap-2 rounded-panel bg-surface-inset p-3', className)}>
        {['w-2/3', 'w-1/2', 'w-3/5', 'w-1/3'].map((width) => (
          <Skeleton key={width} className={cn('h-3', width)} />
        ))}
      </div>
    );
  }

  return (
    <div data-slot="code-block" className={cn('group relative rounded-panel bg-surface-inset', className)}>
      {lines.length === 0 ? (
        <p className="p-3 text-13 text-ink-2">空</p>
      ) : (
        <pre
          className={cn(
            'p-3 pr-12 font-mono text-12 leading-5 text-ink',
            wrap ? 'break-words whitespace-pre-wrap' : 'overflow-x-auto whitespace-pre',
          )}
        >
          <code>
            {lines.slice(0, visible).map((line, i) => (
              // 行在折叠与高亮切换时位置不变，用下标作 key 不会错配。
              <span key={i} className="block min-h-5">
                {tokens?.[i]
                  ? tokens[i].map((token, j) => (
                      <span key={j} style={{ color: token.color }}>
                        {token.content}
                      </span>
                    ))
                  : line}
              </span>
            ))}
          </code>
        </pre>
      )}
      {lines.length > 0 && (
        <Button
          variant="ghost"
          size="icon-sm"
          aria-label={state === 'copied' ? `已复制${label}` : `复制${label}`}
          className="absolute top-2 right-2"
          onClick={() => void copy(code)}
        >
          <Icon
            glyph={state === 'copied' ? CheckIcon : state === 'failed' ? WarningCircleIcon : CopyIcon}
            className={cn(state === 'copied' && 'text-success', state === 'failed' && 'text-danger')}
          />
        </Button>
      )}
      {collapsible && (
        <div className="border-t border-rule px-3 py-1">
          <Button variant="ghost" size="sm" className="-ml-2" onClick={() => setExpanded((v) => !v)}>
            {expanded ? '收起' : `展开全部（共 ${lines.length} 行）`}
          </Button>
        </div>
      )}
    </div>
  );
}
