import { useSuspenseQuery } from '@tanstack/react-query';
import { useState } from 'react';

import { KeyValueList } from '@/components/domain/key-value-list';
import { RelativeTime } from '@/components/domain/relative-time';
import { StatusBadge } from '@/components/domain/status-badge';
import {
  Sheet,
  SheetBody,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
} from '@/components/ui/sheet';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { useProject } from '@/lib/access';
import type { Environment, EnvironmentRevision } from '@/lib/api/types';
import { formatInstant, middleEllipsis } from '@/lib/format';

import { revisionsQuery } from '../api/queries';
import { allowlistDelta, sizeText, TIER_LABEL } from '../model/environment';

export interface RevisionHistoryProps {
  environment: Environment;
  /** 当前用户的主体标识：自己产生的修订写"你"。 */
  me: string;
}

function allowlistText(list: readonly string[], tier: EnvironmentRevision['tier']): string {
  if (tier === 'none') return '不适用';
  return list.length === 0 ? '不出网' : `${list.length} 个域名`;
}

function RevisionSheet({
  revision,
  current,
  me,
  onClose,
}: {
  revision: EnvironmentRevision | null;
  current: Environment;
  me: string;
  onClose: () => void;
}) {
  const delta = revision ? allowlistDelta(revision.egress_allowlist, current.egress_allowlist) : null;
  const isCurrent = revision?.revision === current.revision;
  return (
    <Sheet open={revision !== null} onOpenChange={(open) => !open && onClose()}>
      <SheetContent size="md">
        {revision && (
          <>
            <SheetHeader>
              <SheetTitle>修订 {revision.revision}</SheetTitle>
              <SheetDescription>
                {revision.created_by === me ? '你' : middleEllipsis(revision.created_by, 8, 4)}产生于{' '}
                {formatInstant(revision.created_at)}
              </SheetDescription>
            </SheetHeader>
            <SheetBody label={`修订 ${revision.revision} 的配置`} className="flex flex-col gap-6">
              <KeyValueList
                labelWidth="narrow"
                items={[
                  { label: '档位', value: TIER_LABEL[revision.tier] },
                  { label: '规格', value: sizeText(revision.size) },
                ]}
              />
              {revision.tier === 'sandbox' && (
                <section aria-labelledby="revision-allowlist" className="flex flex-col gap-2">
                  <h3 id="revision-allowlist" className="text-13 font-semibold">
                    出网白名单
                  </h3>
                  {revision.egress_allowlist.length === 0 ? (
                    <p className="text-13 text-ink-2">不出网</p>
                  ) : (
                    <ul className="flex flex-col border-t border-rule">
                      {revision.egress_allowlist.map((d) => (
                        <li key={d} className="flex h-9 items-center border-b border-rule font-mono text-12">
                          {d}
                        </li>
                      ))}
                    </ul>
                  )}
                </section>
              )}
              {!isCurrent && delta && (
                <section aria-labelledby="revision-delta" className="flex flex-col gap-2">
                  <h3 id="revision-delta" className="text-13 font-semibold">
                    到当前修订 {current.revision} 的变化
                  </h3>
                  <KeyValueList
                    labelWidth="narrow"
                    items={[
                      {
                        label: '档位',
                        value:
                          revision.tier === current.tier
                            ? '未变'
                            : `${TIER_LABEL[revision.tier]}改为${TIER_LABEL[current.tier]}`,
                      },
                      {
                        label: '规格',
                        value:
                          revision.size === current.size
                            ? '未变'
                            : `${sizeText(revision.size) ?? '无'}改为${sizeText(current.size) ?? '无'}`,
                      },
                      {
                        label: '新增',
                        value: delta.added.length ? delta.added.join('、') : null,
                        mono: true,
                      },
                      {
                        label: '移除',
                        value: delta.removed.length ? delta.removed.join('、') : null,
                        mono: true,
                      },
                    ]}
                  />
                </section>
              )}
            </SheetBody>
          </>
        )}
      </SheetContent>
    </Sheet>
  );
}

/**
 * 修订历史：每个修订的档位、规格、白名单、修改人与时间（新的在前）；点一行在抽屉里查看那个修订的完整配置与到当前修订的变化。
 */
export function RevisionHistory({ environment, me }: RevisionHistoryProps) {
  const { tenantId, projectId } = useProject();
  const revisions = useSuspenseQuery(revisionsQuery({ tenantId, projectId }, environment.id)).data;
  const [open, setOpen] = useState<EnvironmentRevision | null>(null);
  return (
    <>
      <Table aria-label="修订历史" className="table-fixed">
        <colgroup>
          <col style={{ width: 112 }} />
          <col style={{ width: 96 }} />
          <col />
          <col style={{ width: 112 }} />
          <col style={{ width: 112 }} />
          <col style={{ width: 120 }} />
        </colgroup>
        <TableHeader>
          <TableRow className="hover:bg-transparent">
            <TableHead>修订</TableHead>
            <TableHead>档位</TableHead>
            <TableHead>规格</TableHead>
            <TableHead>出网</TableHead>
            <TableHead>修改人</TableHead>
            <TableHead>时间</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {[...revisions].reverse().map((r) => (
            <TableRow
              key={r.revision}
              tabIndex={0}
              aria-label={`查看修订 ${r.revision}`}
              className="cursor-pointer"
              onClick={() => setOpen(r)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' || e.key === ' ') {
                  e.preventDefault();
                  setOpen(r);
                }
              }}
            >
              <TableCell>
                <span className="flex items-center gap-2">
                  <span className="tabular-nums">{r.revision}</span>
                  {r.revision === environment.revision && (
                    <StatusBadge of="custom" kind="success" label="当前" />
                  )}
                </span>
              </TableCell>
              <TableCell>{TIER_LABEL[r.tier]}</TableCell>
              <TableCell className="truncate">
                {sizeText(r.size) ?? <span className="text-ink-2">无</span>}
              </TableCell>
              <TableCell>{allowlistText(r.egress_allowlist, r.tier)}</TableCell>
              <TableCell className="truncate font-mono text-12 text-ink-2">
                {r.created_by === me ? '你' : middleEllipsis(r.created_by, 8, 4)}
              </TableCell>
              <TableCell>
                <RelativeTime value={r.created_at} className="text-ink-2" />
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
      <RevisionSheet revision={open} current={environment} me={me} onClose={() => setOpen(null)} />
    </>
  );
}
