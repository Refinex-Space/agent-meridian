import { useState, type ReactNode } from 'react';

import { ErrorState } from '../error-state';
import { StatusBadge } from '../status-badge';
import { Button } from '@/components/ui/button';
import { Command, CommandGroup, CommandInput, CommandItem, CommandList } from '@/components/ui/command';
import { controlHeight, controlSurface } from '@/components/ui/field-styles';
import { CaretDownIcon, CheckIcon, PlusIcon } from '@/components/ui/glyphs';
import { Icon } from '@/components/ui/icon';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { Skeleton } from '@/components/ui/skeleton';
import { cn } from '@/lib/cn';

/** 资源的当前状态：只给需要注意的状态（不可用、已删除），正常的资源不贴标签。 */
export interface ResourceStatus {
  kind: 'failure' | 'idle';
  label: string;
}

export interface ResourceOption {
  id: string;
  name: string;
  /** 右侧的补充信息（"36 份文档"、"上下文 128K"）。 */
  detail?: string;
  status?: ResourceStatus;
  /** 名字是机器名（模型、技能、MCP 服务）时用等宽字体。 */
  mono?: boolean;
  /** 已选或不能选（例如 Embedding 模型）。 */
  disabled?: boolean;
}

/** 选项列表的加载结果（来自 TanStack Query）。 */
export interface ResourceSource {
  options: readonly ResourceOption[];
  loading: boolean;
  error: unknown;
  onRetry: () => void;
}

interface ResourceListProps {
  source: ResourceSource;
  /** 搜索框的占位文字（"搜索模型"）。 */
  searchLabel: string;
  /** 没有任何资源时的一句话（"本租户还没有对话模型"）。 */
  emptyLabel: string;
  selected?: string | null;
  /** 列表顶部的"不选"项（"跟随模型目录"）。 */
  nullOption?: string;
  onSelect: (id: string | null) => void;
}

const NULL_VALUE = '__none__';

function ResourceList({
  source,
  searchLabel,
  emptyLabel,
  selected,
  nullOption,
  onSelect,
}: ResourceListProps) {
  if (source.loading)
    return (
      <div aria-busy="true" aria-label="正在加载" className="flex flex-col gap-2 p-3">
        {[0, 1, 2].map((i) => (
          <Skeleton key={i} className="h-5 w-full" />
        ))}
      </div>
    );
  if (source.error)
    return (
      <ErrorState
        size="inline"
        title="无法加载可选项"
        error={source.error}
        onRetry={source.onRetry}
        className="p-3"
      />
    );
  return (
    <Command>
      <CommandInput placeholder={searchLabel} aria-label={searchLabel} />
      <CommandList empty={source.options.length === 0 ? emptyLabel : '没有匹配的结果'}>
        <CommandGroup>
          {nullOption && (
            <CommandItem value={`${NULL_VALUE} ${nullOption}`} onSelect={() => onSelect(null)}>
              <span className="flex-1 truncate">{nullOption}</span>
              {selected === null && <Icon glyph={CheckIcon} className="text-accent" />}
            </CommandItem>
          )}
          {source.options.map((option) => (
            <CommandItem
              key={option.id}
              value={`${option.id} ${option.name}`}
              disabled={option.disabled}
              onSelect={() => onSelect(option.id)}
            >
              <span className={cn('min-w-0 flex-1 truncate', option.mono && 'font-mono text-12')}>
                {option.name}
              </span>
              {option.status && (
                <StatusBadge of="custom" kind={option.status.kind} label={option.status.label} />
              )}
              {option.detail && (
                <span className="shrink-0 text-12 text-ink-2 tabular-nums">{option.detail}</span>
              )}
              {selected === option.id && <Icon glyph={CheckIcon} className="text-accent" />}
            </CommandItem>
          ))}
        </CommandGroup>
      </CommandList>
    </Command>
  );
}

export interface ResourceSelectProps extends Omit<ResourceListProps, 'onSelect' | 'selected'> {
  /** 触发器的 id（与 FormField 的 id 相同）。 */
  id: string;
  value: string | null;
  onChange: (id: string | null) => void;
  /** 当前值不在选项里时的显示（资源已删除，或不能用于这里）。 */
  missingLabel?: string;
  disabled?: boolean;
  invalid?: boolean;
  describedBy?: string;
  /** 外部的标签（FormField）之外，读屏需要更具体的名字时给出。 */
  ariaLabel?: string;
}

/**
 * 可搜索的资源选择器：触发器与输入框同一外观，弹出层里是可搜索的列表，选项带资源当前的状态（MCP 服务不可用、模型已删除）。
 * 模型、知识库的查询改写模型等单选字段用它。
 */
export function ResourceSelect({
  id,
  value,
  onChange,
  source,
  missingLabel = '已删除或不可用',
  nullOption,
  disabled,
  invalid,
  describedBy,
  ariaLabel,
  ...list
}: ResourceSelectProps) {
  const [open, setOpen] = useState(false);
  const current = source.options.find((o) => o.id === value);
  let display: ReactNode;
  if (value === null) display = <span className="truncate text-ink-2">{nullOption ?? '未选择'}</span>;
  else if (current)
    display = <span className={cn('truncate', current.mono && 'font-mono text-12')}>{current.name}</span>;
  else if (source.loading) display = <Skeleton className="h-4 w-32" />;
  else
    display = (
      <span className="flex min-w-0 items-center gap-2">
        <span className="truncate font-mono text-12">{value}</span>
        <StatusBadge of="custom" kind="failure" label={missingLabel} />
      </span>
    );
  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild disabled={disabled}>
        <button
          id={id}
          type="button"
          aria-describedby={describedBy}
          aria-label={ariaLabel}
          className={cn(
            controlSurface,
            controlHeight.md,
            'flex items-center justify-between gap-2 text-left whitespace-nowrap',
            // 按钮不支持 aria-invalid：错误经 aria-describedby 读出，边框按出错显示。
            invalid && 'border-danger hover:border-danger',
          )}
        >
          <span className="flex min-w-0 flex-1 items-center">{display}</span>
          {current?.status && (
            <StatusBadge of="custom" kind={current.status.kind} label={current.status.label} />
          )}
          <Icon glyph={CaretDownIcon} className="shrink-0 text-ink-2" />
        </button>
      </PopoverTrigger>
      <PopoverContent className="w-(--radix-popover-trigger-width) min-w-80 p-0">
        <ResourceList
          {...list}
          source={source}
          nullOption={nullOption}
          selected={value}
          onSelect={(next) => {
            setOpen(false);
            onChange(next);
          }}
        />
      </PopoverContent>
    </Popover>
  );
}

export interface ResourceAddProps extends Omit<ResourceListProps, 'onSelect' | 'selected' | 'nullOption'> {
  /** 按钮文字（"添加技能"）。 */
  label: string;
  onAdd: (id: string) => void;
}

/** 列表字段（技能、知识库、MCP 服务）的"添加"按钮：弹出可搜索的列表，已添加的项不可选。 */
export function ResourceAdd({ label, onAdd, ...list }: ResourceAddProps) {
  const [open, setOpen] = useState(false);
  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <Button size="sm" variant="ghost" className="-ml-2 self-start" icon={<Icon glyph={PlusIcon} />}>
          {label}
        </Button>
      </PopoverTrigger>
      <PopoverContent className="w-96 p-0">
        <ResourceList
          {...list}
          onSelect={(id) => {
            setOpen(false);
            if (id) onAdd(id);
          }}
        />
      </PopoverContent>
    </Popover>
  );
}
