import { useMemo, useRef, useState, type KeyboardEvent } from 'react';

import { cn } from '@/lib/cn';

import { StatusGlyph, type StatusKind } from '../status-badge';

export interface ThreadTreeItem {
  /** 线程 ID（thr_…）。 */
  id: string;
  /** 父线程 ID；主线程为 null。 */
  parentId: string | null;
  /** 主线程写"主线程"，子线程写子智能体名。 */
  label: string;
  status: StatusKind;
  /** 需要注意的结束原因（"预算耗尽"）；正常结束不写，只由空心圆表达。 */
  note?: string;
}

export interface ThreadTreeProps {
  items: readonly ThreadTreeItem[];
  selectedId: string | null;
  onSelect: (id: string) => void;
  /** 树的可访问名称，默认"线程"。 */
  label?: string;
  className?: string;
}

interface Node {
  item: ThreadTreeItem;
  level: number;
  children: Node[];
}

function buildTree(items: readonly ThreadTreeItem[]): Node[] {
  const byId = new Map(items.map((item) => [item.id, { item, level: 1, children: [] as Node[] }]));
  const roots: Node[] = [];
  for (const node of byId.values()) {
    const parent = node.item.parentId ? byId.get(node.item.parentId) : undefined;
    if (parent) parent.children.push(node);
    else roots.push(node);
  }
  const setLevel = (nodes: Node[], level: number) => {
    for (const n of nodes) {
      n.level = level;
      setLevel(n.children, level + 1);
    }
  };
  setLevel(roots, 1);
  return roots;
}

/**
 * 线程树：主线程与子线程的层级、状态符号与需要注意的结束原因。符合 WAI-ARIA 树的键盘交互：
 * 上下移动、右键展开或进入子线程、左键收起或回到父线程、Home/End、回车或空格选择。
 */
export function ThreadTree({ items, selectedId, onSelect, label = '线程', className }: ThreadTreeProps) {
  const roots = useMemo(() => buildTree(items), [items]);
  const [collapsed, setCollapsed] = useState<ReadonlySet<string>>(new Set());
  const [focusId, setFocusId] = useState<string | null>(null);
  const refs = useRef(new Map<string, HTMLDivElement>());

  // 当前可见的节点，按显示顺序。
  const visible: Node[] = [];
  const walk = (nodes: Node[]) => {
    for (const n of nodes) {
      visible.push(n);
      if (!collapsed.has(n.item.id)) walk(n.children);
    }
  };
  walk(roots);

  const tabStop = focusId ?? selectedId ?? visible[0]?.item.id ?? null;

  const focus = (id: string | undefined) => {
    if (!id) return;
    setFocusId(id);
    refs.current.get(id)?.focus();
  };

  const toggle = (id: string, open: boolean) =>
    setCollapsed((prev) => {
      const next = new Set(prev);
      if (open) next.delete(id);
      else next.add(id);
      return next;
    });

  const onKeyDown = (e: KeyboardEvent<HTMLDivElement>, node: Node) => {
    if (e.target !== e.currentTarget) return;
    const index = visible.indexOf(node);
    const hasChildren = node.children.length > 0;
    const open = hasChildren && !collapsed.has(node.item.id);
    switch (e.key) {
      case 'ArrowDown':
        focus(visible[index + 1]?.item.id);
        break;
      case 'ArrowUp':
        focus(visible[index - 1]?.item.id);
        break;
      case 'ArrowRight':
        if (hasChildren && !open) toggle(node.item.id, true);
        else if (open) focus(node.children[0]?.item.id);
        break;
      case 'ArrowLeft':
        if (open) toggle(node.item.id, false);
        else focus(node.item.parentId ?? undefined);
        break;
      case 'Home':
        focus(visible[0]?.item.id);
        break;
      case 'End':
        focus(visible.at(-1)?.item.id);
        break;
      case 'Enter':
      case ' ':
        onSelect(node.item.id);
        break;
      default:
        return;
    }
    e.preventDefault();
  };

  const renderNodes = (nodes: Node[]) =>
    nodes.map((node) => {
      const { item } = node;
      const hasChildren = node.children.length > 0;
      const open = hasChildren && !collapsed.has(item.id);
      const selected = item.id === selectedId;
      return (
        <div
          key={item.id}
          ref={(el) => {
            if (el) refs.current.set(item.id, el);
            else refs.current.delete(item.id);
          }}
          role="treeitem"
          aria-level={node.level}
          aria-selected={selected}
          aria-expanded={hasChildren ? open : undefined}
          tabIndex={item.id === tabStop ? 0 : -1}
          onKeyDown={(e) => onKeyDown(e, node)}
          onFocus={(e) => e.target === e.currentTarget && setFocusId(item.id)}
          onClick={(e) => {
            e.stopPropagation();
            setFocusId(item.id);
            onSelect(item.id);
          }}
          className="rounded-control outline-offset-[-2px] select-none"
        >
          <div
            className={cn(
              'grid min-h-8 cursor-pointer grid-cols-[12px_minmax(0,1fr)] items-center gap-x-2 rounded-control py-1 pr-2 text-13 transition-colors duration-150 ease-out',
              selected ? 'bg-accent-weak' : 'hover:bg-surface-hover',
            )}
            style={{ paddingLeft: `${8 + (node.level - 1) * 16}px` }}
          >
            <StatusGlyph kind={item.status} />
            <span className={cn('truncate leading-5', node.level > 1 && 'font-mono text-12')}>
              {item.label}
            </span>
            {item.note && <span className="col-start-2 text-12 leading-4 text-danger">{item.note}</span>}
          </div>
          {open && (
            <div role="group" className="relative">
              <span
                aria-hidden
                className="absolute top-0 bottom-3 w-px bg-rule"
                style={{ left: `${13 + (node.level - 1) * 16}px` }}
              />
              {renderNodes(node.children)}
            </div>
          )}
        </div>
      );
    });

  return (
    <div role="tree" aria-label={label} data-slot="thread-tree" className={cn('flex flex-col', className)}>
      {renderNodes(roots)}
    </div>
  );
}
