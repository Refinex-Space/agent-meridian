import { EmptyState } from '@/components/domain/empty-state';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { cn } from '@/lib/cn';

import type { DiffLine, DiffRow } from '../model/diff';

export interface SpecDiffProps {
  rows: readonly DiffRow[];
  /** 两列的标题（"v12"、"v13 草稿"）。 */
  baseLabel: string;
  targetLabel: string;
  /** 表格的读屏名称。 */
  label: string;
  className?: string;
}

function Absent() {
  return <span className="text-ink-2">无</span>;
}

function Lines({ lines }: { lines: readonly DiffLine[] }) {
  return (
    <pre
      role="region"
      tabIndex={0}
      aria-label="逐行差异"
      className="max-h-80 overflow-auto rounded-control bg-surface-inset py-2 font-mono text-12 leading-5 whitespace-pre-wrap"
    >
      {lines.map((line, i) => (
        <div
          key={i}
          className={cn(
            'grid grid-cols-[20px_minmax(0,1fr)] px-2',
            line.kind === 'added' && 'text-success',
            line.kind === 'removed' && 'text-danger line-through decoration-danger/60',
            line.kind === 'same' && 'text-ink-2',
          )}
        >
          <span aria-hidden>{line.kind === 'added' ? '+' : line.kind === 'removed' ? '−' : ' '}</span>
          <span>
            <span className="sr-only">
              {line.kind === 'added' ? '新增：' : line.kind === 'removed' ? '删除：' : ''}
            </span>
            {line.text || ' '}
          </span>
        </div>
      ))}
    </pre>
  );
}

/**
 * 两个版本规格的差异：一行一个字段，左列字段名（下方是 JSON 指针），两列取值；字符串列表写增减，提示词逐行比较。
 */
export function SpecDiff({ rows, baseLabel, targetLabel, label, className }: SpecDiffProps) {
  if (rows.length === 0)
    return <EmptyState variant="clear" size="inline" title="两个版本的规格相同" className={className} />;
  return (
    <Table aria-label={label} className={cn('table-fixed', className)}>
      <colgroup>
        <col style={{ width: '28%' }} />
        <col />
        <col />
      </colgroup>
      <TableHeader>
        <TableRow className="hover:bg-transparent">
          <TableHead>字段</TableHead>
          <TableHead>{baseLabel}</TableHead>
          <TableHead>{targetLabel}</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {rows.map((row) => (
          <TableRow key={row.key} className="align-top hover:bg-transparent">
            <TableCell className="py-3">
              <div className="flex flex-col gap-1">
                <span className="font-medium">{row.label}</span>
                <span className="truncate font-mono text-12 text-ink-2">{row.pointer}</span>
              </div>
            </TableCell>
            {row.lines ? (
              <TableCell colSpan={2} className="py-3">
                <Lines lines={row.lines} />
              </TableCell>
            ) : row.list ? (
              <>
                <TableCell className="py-3 break-words whitespace-normal">
                  {row.list.removed.length > 0 ? (
                    <span className="text-danger">
                      <span className="text-ink-2">移除 </span>
                      <span className="font-mono text-12 line-through">{row.list.removed.join('、')}</span>
                    </span>
                  ) : (
                    <Absent />
                  )}
                </TableCell>
                <TableCell className="py-3 break-words whitespace-normal">
                  {row.list.added.length > 0 ? (
                    <span className="text-success">
                      <span className="text-ink-2">新增 </span>
                      <span className="font-mono text-12">{row.list.added.join('、')}</span>
                    </span>
                  ) : (
                    <Absent />
                  )}
                </TableCell>
              </>
            ) : (
              <>
                <TableCell
                  className={cn('py-3 break-words whitespace-normal', row.kind !== 'added' && 'text-danger')}
                >
                  {row.before ?? <Absent />}
                </TableCell>
                <TableCell
                  className={cn(
                    'py-3 break-words whitespace-normal',
                    row.kind !== 'removed' && 'text-success',
                  )}
                >
                  {row.after ?? <Absent />}
                </TableCell>
              </>
            )}
          </TableRow>
        ))}
      </TableBody>
    </Table>
  );
}
