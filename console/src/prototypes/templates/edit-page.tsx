import { useEffect, useRef, useState, type ReactNode, type RefObject } from 'react';

import { PageHeader } from '@/components/domain/page-header';
import { StatusGlyph } from '@/components/domain/status-badge';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { cn } from '@/lib/cn';
import { formatClock } from '@/lib/format';

export interface EditGroup {
  /** 分组的锚点 ID，与 `EditSection` 的 `id` 相同。 */
  id: string;
  label: string;
  /** 分组里有校验错误：导航上标出，提交时滚到第一个出错的分组。 */
  invalid?: boolean;
}

export interface SaveBarState {
  /** 有未保存的更改。 */
  dirty: boolean;
  saving?: boolean;
  /** 最近一次保存的时间（ISO）：今天的写"已保存于 10:42"，更早的带日期；保存后表单就地变为已保存，不另弹通知。 */
  savedAt?: string | null;
  /** 校验错误数：保存栏写明，保存按钮仍可点（点了聚焦第一个错误）。 */
  errorCount?: number;
  saveLabel: string;
  onSave: () => void;
  onDiscard: () => void;
  /** 当前用户不能保存时的说明（按钮由调用方用 PermissionGate 包装时不传）。 */
  readOnly?: string;
  /** 加载中：不写状态。 */
  hideStatus?: boolean;
}

export interface EditPageProps {
  title: ReactNode;
  meta?: ReactNode;
  /** 页头右侧的操作（"发布版本"），保存在底部的保存栏。 */
  actions?: ReactNode;
  groups: readonly EditGroup[];
  /** 加载中：分组导航与表单显示骨架。 */
  loading?: boolean;
  /** 整页的错误（加载失败）代替表单。 */
  error?: ReactNode;
  /** 表单与保存栏之间的就地提示（保存失败）。 */
  notice?: ReactNode;
  saveBar: SaveBarState;
  /** 表单分组（EditSection）。 */
  children: ReactNode;
}

/** 表单的一个分组：标题 16px，与左侧导航对应。 */
export function EditSection({ id, title, children }: { id: string; title: string; children: ReactNode }) {
  return (
    <section id={id} aria-labelledby={`${id}-title`} className="flex scroll-mt-6 flex-col gap-4">
      <h2 id={`${id}-title`} className="text-16 font-semibold">
        {title}
      </h2>
      {children}
    </section>
  );
}

/** 滚动时高亮当前分组：标题已经滚过容器顶部往下 96px 的最后一个分组；滚到底时是最后一个。 */
function useActiveGroup(container: RefObject<HTMLDivElement | null>, ids: readonly string[]) {
  const [active, setActive] = useState(ids[0] ?? '');
  const key = ids.join(' ');
  useEffect(() => {
    const root = container.current;
    if (!root) return undefined;
    const update = () => {
      const top = root.getBoundingClientRect().top + 96;
      const atBottom = root.scrollTop + root.clientHeight >= root.scrollHeight - 2;
      let current = ids[0] ?? '';
      for (const id of ids) {
        const el = root.querySelector(`#${CSS.escape(id)}`);
        if (el && el.getBoundingClientRect().top <= top) current = id;
      }
      setActive(atBottom ? (ids.at(-1) ?? current) : current);
    };
    update();
    root.addEventListener('scroll', update, { passive: true });
    return () => root.removeEventListener('scroll', update);
    // ids 的内容由 key 表示，数组本身每次渲染都是新的。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [container, key]);
  return [active, setActive] as const;
}

/** 保存时间：今天只写时刻，更早的带日期。 */
function savedLabel(iso: string): string {
  const date = new Date(iso);
  const time = formatClock(iso).slice(0, 5);
  const today = new Date();
  return date.toDateString() === today.toDateString()
    ? time
    : `${date.getMonth() + 1} 月 ${date.getDate()} 日 ${time}`;
}

function SaveBar({ state }: { state: SaveBarState }) {
  const {
    dirty,
    saving = false,
    savedAt,
    errorCount = 0,
    saveLabel,
    onSave,
    onDiscard,
    readOnly,
    hideStatus,
  } = state;
  let status: ReactNode;
  if (hideStatus) status = null;
  else if (readOnly) status = <span className="text-ink-2">{readOnly}</span>;
  else if (errorCount > 0)
    status = (
      <span className="flex items-center gap-2 text-danger">
        <StatusGlyph kind="failure" />有 {errorCount} 处需要修改
      </span>
    );
  else if (dirty)
    status = (
      <span className="flex items-center gap-2 font-medium text-brass-ink">
        <StatusGlyph kind="requires_action" />
        有未保存的更改
      </span>
    );
  else if (savedAt) status = <span className="text-ink-2">已保存于 {savedLabel(savedAt)}</span>;
  else status = <span className="text-ink-2">没有更改</span>;

  return (
    <div className="flex h-14 shrink-0 items-center gap-3 border-t border-rule bg-surface px-6 text-13">
      <div role="status" className="min-w-0 flex-1">
        {status}
      </div>
      {!readOnly && (
        <>
          <Button disabled={!dirty || saving} onClick={onDiscard}>
            放弃更改
          </Button>
          <Button variant="primary" disabled={!dirty} pending={saving} onClick={onSave}>
            {saveLabel}
          </Button>
        </>
      )}
    </div>
  );
}

/** 表单的骨架：与分组标题、字段标签和输入框同样的行高。 */
function FormSkeleton() {
  return (
    <div aria-hidden className="flex flex-col gap-4">
      <Skeleton className="h-5 w-24" />
      {['w-16', 'w-12', 'w-20'].map((w, i) => (
        <div key={w} className="flex flex-col gap-2">
          <Skeleton className={cn('h-4', w)} />
          <Skeleton className={cn('h-8', i === 1 ? 'w-full' : 'w-100')} />
        </div>
      ))}
    </div>
  );
}

/**
 * 编辑页模板：左侧表单分组导航（滚动时高亮当前分组，出错的分组标出），右侧表单最大 880px，底部固定的保存栏。
 */
export function EditPage({
  title,
  meta,
  actions,
  groups,
  loading = false,
  error,
  notice,
  saveBar,
  children,
}: EditPageProps) {
  const scrollRef = useRef<HTMLDivElement>(null);
  const ids = groups.map((g) => g.id);
  const [active, setActive] = useActiveGroup(scrollRef, ids);

  const jump = (id: string) => {
    setActive(id);
    scrollRef.current?.querySelector(`#${CSS.escape(id)}`)?.scrollIntoView({ block: 'start' });
  };

  return (
    <div className="flex h-full min-h-0 flex-col bg-surface">
      <div className="shrink-0 border-b border-rule px-6">
        <PageHeader title={title} meta={meta} actions={loading ? undefined : actions} loading={loading} />
      </div>
      {error ?? (
        <>
          <div
            ref={scrollRef}
            className="relative min-h-0 flex-1 overflow-y-auto scroll-smooth motion-reduce:scroll-auto"
          >
            <div className="flex gap-12 px-6">
              <nav aria-label="表单分组" className="sticky top-0 w-44 shrink-0 self-start py-6">
                {loading ? (
                  <div aria-hidden className="flex flex-col gap-4 px-2 pt-2">
                    {groups.map((g) => (
                      <Skeleton key={g.id} className="h-3 w-20" />
                    ))}
                  </div>
                ) : (
                  <ul className="flex flex-col gap-1">
                    {groups.map((group) => {
                      const current = group.id === active;
                      return (
                        <li key={group.id}>
                          <a
                            href={`#${group.id}`}
                            aria-current={current ? 'location' : undefined}
                            onClick={(e) => {
                              e.preventDefault();
                              jump(group.id);
                            }}
                            className={cn(
                              'relative flex h-8 items-center gap-2 rounded-control px-2 text-13',
                              current ? 'font-medium text-ink' : 'text-ink-2 hover:text-ink',
                            )}
                          >
                            <span
                              aria-hidden
                              className={cn(
                                'absolute inset-y-1 left-0 w-0.5 rounded-control bg-accent transition-opacity duration-150',
                                current ? 'opacity-100' : 'opacity-0',
                              )}
                            />
                            <span className="flex-1 truncate">{group.label}</span>
                            {group.invalid && (
                              <>
                                <StatusGlyph kind="failure" />
                                <span className="sr-only">有错误</span>
                              </>
                            )}
                          </a>
                        </li>
                      );
                    })}
                  </ul>
                )}
              </nav>
              <div className="flex max-w-220 min-w-0 flex-1 flex-col gap-12 pt-6 pb-12">
                {loading ? <FormSkeleton /> : children}
              </div>
            </div>
          </div>
          {notice}
          <SaveBar
            state={loading ? { ...saveBar, dirty: false, savedAt: null, hideStatus: true } : saveBar}
          />
        </>
      )}
    </div>
  );
}
