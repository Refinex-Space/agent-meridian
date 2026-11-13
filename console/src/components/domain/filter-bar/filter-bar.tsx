import { useEffect, useEffectEvent, useRef, useState, type ReactNode } from 'react';

import { Button } from '@/components/ui/button';
import {
  DropdownMenu,
  DropdownMenuCheckboxItem,
  DropdownMenuContent,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { CaretDownIcon, MagnifyingGlassIcon, XIcon } from '@/components/ui/glyphs';
import { Icon } from '@/components/ui/icon';
import { Input } from '@/components/ui/input';
import { cn } from '@/lib/cn';
import { formatInteger } from '@/lib/format';

import { emptyValues, hasActiveFilters, type FilterDef, type FilterValues } from './filters';

export interface FilterBarProps {
  filters: readonly FilterDef[];
  value: FilterValues;
  onChange: (next: FilterValues) => void;
  /** 搜索框；不给时不显示。`label` 是可访问名称，`placeholder` 写可以搜什么。 */
  search?: { label: string; placeholder: string };
  /** 结果数，显示在右侧（"128 个结果"）；加载中不传。 */
  resultCount?: number;
  /** 右侧的其他控件（例如表格的列显隐）。 */
  actions?: ReactNode;
  className?: string;
}

const SEARCH_DEBOUNCE_MS = 300;

/** 触发按钮上的摘要：没选时只有名称，选了一项显示"名称：值"，多项显示"名称：值等 N 项"。 */
function summary(def: FilterDef, selected: readonly string[]): string {
  if (selected.length === 0) return def.label;
  const first = def.options.find((o) => o.value === selected[0])?.label ?? selected[0];
  return selected.length === 1 ? `${def.label}：${first}` : `${def.label}：${first}等 ${selected.length} 项`;
}

function FilterMenu({
  def,
  selected,
  onSelect,
}: {
  def: FilterDef;
  selected: readonly string[];
  onSelect: (values: string[]) => void;
}) {
  const active = selected.length > 0;
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button
          size="sm"
          data-active={active || undefined}
          className={cn('max-w-64', active && 'border-accent/60 text-accent hover:border-accent')}
        >
          <span className="truncate">{summary(def, selected)}</span>
          <Icon glyph={CaretDownIcon} className="text-ink-2" />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent className="min-w-48">
        {def.multiple ? (
          def.options.map((option) => (
            <DropdownMenuCheckboxItem
              key={option.value}
              checked={selected.includes(option.value)}
              // 多选时选一项不关闭菜单，便于连续勾选。
              onSelect={(e) => e.preventDefault()}
              onCheckedChange={(checked) =>
                onSelect(
                  checked
                    ? def.options
                        .map((o) => o.value)
                        .filter((v) => v === option.value || selected.includes(v))
                    : selected.filter((v) => v !== option.value),
                )
              }
            >
              {option.label}
            </DropdownMenuCheckboxItem>
          ))
        ) : (
          <DropdownMenuRadioGroup value={selected[0] ?? ''} onValueChange={(v) => onSelect(v ? [v] : [])}>
            <DropdownMenuRadioItem value="">不限</DropdownMenuRadioItem>
            {def.options.map((option) => (
              <DropdownMenuRadioItem key={option.value} value={option.value}>
                {option.label}
              </DropdownMenuRadioItem>
            ))}
          </DropdownMenuRadioGroup>
        )}
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

/** 搜索框：输入停顿 300ms 后提交；`/` 聚焦（焦点已在输入控件里时不抢）。 */
function SearchBox({
  label,
  placeholder,
  value,
  onCommit,
}: {
  label: string;
  placeholder: string;
  value: string;
  onCommit: (q: string) => void;
}) {
  const [draft, setDraft] = useState(value);
  const inputRef = useRef<HTMLInputElement>(null);
  const committed = useRef(value);

  // 外部改了取值（清除筛选、前进后退）时同步输入框。
  useEffect(() => {
    if (value !== committed.current) {
      committed.current = value;
      setDraft(value);
    }
  }, [value]);

  // 父组件每次渲染都会给新的 onCommit；用 Effect Event 读取最新的一个，计时只随输入重置。
  const commit = useEffectEvent((q: string) => {
    committed.current = q;
    onCommit(q);
  });

  useEffect(() => {
    const q = draft.trim();
    if (q === committed.current) return undefined;
    const timer = window.setTimeout(() => commit(q), SEARCH_DEBOUNCE_MS);
    return () => window.clearTimeout(timer);
  }, [draft]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== '/' || e.metaKey || e.ctrlKey || e.altKey) return;
      const target = e.target as HTMLElement | null;
      if (target?.closest('input, textarea, select, [contenteditable="true"]')) return;
      e.preventDefault();
      inputRef.current?.focus();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);

  return (
    <div className="relative w-64">
      <Icon
        glyph={MagnifyingGlassIcon}
        className="pointer-events-none absolute top-1/2 left-2 -translate-y-1/2 text-ink-2"
      />
      <Input
        ref={inputRef}
        type="search"
        size="sm"
        aria-label={label}
        placeholder={placeholder}
        value={draft}
        onChange={(e) => setDraft(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === 'Escape' && draft) {
            e.preventDefault();
            setDraft('');
          }
        }}
        className="pr-8 pl-8 [&::-webkit-search-cancel-button]:hidden"
      />
      {draft && (
        <button
          type="button"
          aria-label="清空搜索"
          onClick={() => {
            setDraft('');
            inputRef.current?.focus();
          }}
          className="absolute top-1/2 right-1 flex size-5 -translate-y-1/2 items-center justify-center rounded-control text-ink-2 hover:bg-surface-hover hover:text-ink"
        >
          <Icon glyph={XIcon} />
        </button>
      )}
    </div>
  );
}

/**
 * 列表页的筛选与搜索：受控组件，取值与 URL 同步由 useUrlFilters 负责。有筛选条件时出现"清除筛选"，
 * 结果数在右侧。
 */
export function FilterBar({
  filters,
  value,
  onChange,
  search,
  resultCount,
  actions,
  className,
}: FilterBarProps) {
  const active = hasActiveFilters(value);
  const commitSearch = (q: string) => onChange({ ...value, q });
  return (
    <div
      data-slot="filter-bar"
      role="search"
      className={cn('flex min-h-8 flex-wrap items-center gap-2', className)}
    >
      {search && (
        <SearchBox
          label={search.label}
          placeholder={search.placeholder}
          value={value.q}
          onCommit={commitSearch}
        />
      )}
      {filters.map((def) => (
        <FilterMenu
          key={def.key}
          def={def}
          selected={value.filters[def.key] ?? []}
          onSelect={(values) => onChange({ ...value, filters: { ...value.filters, [def.key]: values } })}
        />
      ))}
      {active && (
        <Button size="sm" variant="ghost" onClick={() => onChange(emptyValues(filters))}>
          清除筛选
        </Button>
      )}
      <div className="ml-auto flex items-center gap-3">
        {resultCount !== undefined && (
          <span role="status" className="text-12 text-ink-2 tabular-nums">
            {formatInteger(resultCount)} 个结果
          </span>
        )}
        {actions}
      </div>
    </div>
  );
}
