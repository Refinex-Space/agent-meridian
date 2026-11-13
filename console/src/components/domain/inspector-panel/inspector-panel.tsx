import type { ReactNode } from 'react';

import { Button } from '@/components/ui/button';
import { SidebarSimpleIcon } from '@/components/ui/glyphs';
import { Icon } from '@/components/ui/icon';
import { ScrollArea } from '@/components/ui/scroll-area';
import { Skeleton } from '@/components/ui/skeleton';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import { cn } from '@/lib/cn';

import { CodeBlock } from '../code-block';

export interface InspectorPanelProps {
  /** 选中对象的标题（"调用 bash"）；没有选中时不传，显示 `empty`。 */
  title?: ReactNode;
  /** 概要页的内容：属性列表、工具调用、用量等。 */
  children?: ReactNode;
  /** 原始 JSON 页的内容（事件对象），可复制。 */
  raw?: unknown;
  /** 没有选中对象时的一句话。 */
  empty?: ReactNode;
  /** 加载选中对象的详情中。 */
  loading?: boolean;
  /** 给出时标题栏右侧有"收起检查器"。 */
  onCollapse?: () => void;
  className?: string;
}

/** 检查器里的一节：13px 600 字重的标题加内容。 */
export function InspectorSection({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="flex flex-col gap-2">
      <h3 className="text-13 font-semibold">{title}</h3>
      {children}
    </section>
  );
}

/**
 * 右侧详情面板：选中对象的完整内容（概要）与原始 JSON（Shiki 高亮、可复制）；标题栏 48px，可收起。
 * 宽度由所在的工作台布局决定。
 */
export function InspectorPanel({
  title,
  children,
  raw,
  empty = '选择一个事件，查看完整内容与原始 JSON。',
  loading = false,
  onCollapse,
  className,
}: InspectorPanelProps) {
  const selected = title !== undefined;
  return (
    <aside
      data-slot="inspector-panel"
      aria-label="检查器"
      aria-busy={loading || undefined}
      className={cn('flex h-full min-h-0 flex-col bg-surface', className)}
    >
      <div className="flex h-12 shrink-0 items-center gap-2 border-b border-rule pr-2 pl-4">
        <h2 className="min-w-0 flex-1 truncate text-14 font-semibold">
          {loading ? <Skeleton className="h-4 w-40" /> : selected ? title : '检查器'}
        </h2>
        {onCollapse && (
          <Tooltip>
            <TooltipTrigger asChild>
              <Button size="icon-sm" variant="ghost" aria-label="收起检查器" onClick={onCollapse}>
                <Icon glyph={SidebarSimpleIcon} />
              </Button>
            </TooltipTrigger>
            <TooltipContent>收起检查器</TooltipContent>
          </Tooltip>
        )}
      </div>
      {loading ? (
        <div className="flex flex-col gap-3 p-4">
          {['w-3/4', 'w-1/2', 'w-2/3', 'w-1/3'].map((w) => (
            <Skeleton key={w} className={cn('h-3', w)} />
          ))}
        </div>
      ) : !selected ? (
        <p className="p-4 text-13 text-ink-2">{empty}</p>
      ) : (
        <Tabs defaultValue="summary" className="min-h-0 flex-1">
          <TabsList className="px-4">
            <TabsTrigger value="summary">概要</TabsTrigger>
            {raw !== undefined && <TabsTrigger value="json">原始 JSON</TabsTrigger>}
          </TabsList>
          <TabsContent value="summary" className="min-h-0">
            <ScrollArea className="h-full">
              <div className="flex flex-col gap-6 p-4">{children}</div>
            </ScrollArea>
          </TabsContent>
          {raw !== undefined && (
            <TabsContent value="json" className="min-h-0">
              <ScrollArea className="h-full">
                <div className="p-4">
                  <CodeBlock
                    code={JSON.stringify(raw, null, 2)}
                    language="json"
                    label="原始 JSON"
                    collapseAfter={0}
                  />
                </div>
              </ScrollArea>
            </TabsContent>
          )}
        </Tabs>
      )}
    </aside>
  );
}
