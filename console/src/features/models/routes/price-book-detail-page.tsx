import { useQuery, useSuspenseQuery } from '@tanstack/react-query';
import { useState } from 'react';

import { CopyableId } from '@/components/domain/copyable-id';
import { DataRegion } from '@/components/domain/data-region';
import { PermissionGate } from '@/components/domain/permission-gate';
import { RelativeTime } from '@/components/domain/relative-time';
import { DetailPage } from '@/components/templates';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { InfoIcon } from '@/components/ui/glyphs';
import { Icon } from '@/components/ui/icon';
import { Skeleton } from '@/components/ui/skeleton';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import { meQuery, usePermission, useProject } from '@/lib/access';
import type { PriceBook } from '@/lib/api/types';
import { cn } from '@/lib/cn';
import { useCrumb } from '@/lib/crumbs';
import { formatDateTime, middleEllipsis } from '@/lib/format';

import { catalogModelsQuery, priceBookQuery, priceBookVersionsQuery } from '../api/queries';
import { PublishVersionDialog } from '../components/price-book-dialogs';
import { PRICE_FIELDS } from '../model/catalog';

export interface PriceBookDetailPageProps {
  priceBookId: string;
}

/** 版本历史：每个版本的五个单价、生效时间与发布人，当前版本在最上面。 */
function Versions({ priceBook, me }: { priceBook: PriceBook; me: string }) {
  const { tenantId } = useProject();
  const versions = useSuspenseQuery(priceBookVersionsQuery(tenantId, priceBook.id)).data;
  return (
    <Table aria-labelledby="price-versions">
      <TableHeader>
        <TableRow>
          <TableHead className="w-20">版本</TableHead>
          {PRICE_FIELDS.map((f) => (
            <TableHead key={f.key} className="text-right">
              {f.label}
            </TableHead>
          ))}
          <TableHead className="w-40">生效于</TableHead>
          <TableHead className="w-28">发布人</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {versions.map((v) => {
          const current = v.version === priceBook.current_version.version;
          return (
            <TableRow key={v.version} className={cn(current && 'bg-accent-weak')}>
              <TableCell className="tabular-nums">
                v{v.version}
                {current && <span className="ml-2 text-12 text-ink-2">当前</span>}
              </TableCell>
              {PRICE_FIELDS.map((f) => (
                <TableCell key={f.key} className="text-right tabular-nums">
                  {v.prices[f.key]}
                </TableCell>
              ))}
              <TableCell className="whitespace-nowrap tabular-nums">
                {formatDateTime(v.effective_from)}
              </TableCell>
              <TableCell>{v.created_by === me ? '你' : middleEllipsis(v.created_by, 8, 4)}</TableCell>
            </TableRow>
          );
        })}
      </TableBody>
    </Table>
  );
}

function Loaded({ priceBookId }: PriceBookDetailPageProps) {
  const { tenantId } = useProject();
  const priceBook = useSuspenseQuery(priceBookQuery(tenantId, priceBookId)).data;
  const models = useQuery(catalogModelsQuery(tenantId));
  const me = useQuery(meQuery()).data?.subject ?? '';
  const write = usePermission('model_write', '发布价格表的新版本');
  const [publishing, setPublishing] = useState(false);
  useCrumb(priceBook.name);
  const using = (models.data ?? []).filter((m) => m.price_book_id === priceBook.id);

  return (
    <DetailPage
      title={priceBook.name}
      meta={
        <>
          <Badge>当前 v{priceBook.current_version.version}</Badge>
          <span className="font-mono text-12 text-ink-2">{priceBook.currency}</span>
          <CopyableId id={priceBook.id} label="价格表 ID" />
          <span className="text-13 text-ink-2">
            生效于 <RelativeTime value={priceBook.current_version.effective_from} />
          </span>
        </>
      }
      actions={
        <PermissionGate mode="disable" allowed={write.allowed} reason={write.reason}>
          <Button variant="primary" onClick={() => setPublishing(true)}>
            发布新版本
          </Button>
        </PermissionGate>
      }
    >
      <div className="flex max-w-260 flex-col gap-8 pt-6">
        <section aria-labelledby="price-versions" className="flex flex-col gap-3">
          <div className="flex items-center gap-1">
            <h2 id="price-versions" className="text-16 font-semibold">
              版本（{priceBook.currency} / 百万 token）
            </h2>
            <Tooltip>
              <TooltipTrigger asChild>
                <button
                  type="button"
                  aria-label="价格表版本的说明"
                  className="inline-flex rounded-control text-ink-2 hover:text-ink"
                >
                  <Icon glyph={InfoIcon} />
                </button>
              </TooltipTrigger>
              <TooltipContent className="max-w-80">
                新版本发布即生效，之后结算的用量按新价；每条用量记录带着结算时的版本，发布新版本不改历史成本。版本发布后不能修改。
              </TooltipContent>
            </Tooltip>
          </div>
          <DataRegion
            errorTitle="无法加载版本"
            errorSize="inline"
            skeleton={
              <div aria-hidden className="flex flex-col gap-3">
                {[0, 1, 2].map((i) => (
                  <Skeleton key={i} className="h-5 w-full" />
                ))}
              </div>
            }
          >
            <Versions priceBook={priceBook} me={me} />
          </DataRegion>
        </section>
        <section aria-labelledby="price-models" className="flex flex-col gap-3">
          <h2 id="price-models" className="text-16 font-semibold">
            用它计价的模型
          </h2>
          {using.length === 0 ? (
            <p className="text-13 text-ink-2">还没有模型选用这个价格表。</p>
          ) : (
            <ul aria-labelledby="price-models" className="flex flex-wrap gap-2">
              {using.map((m) => (
                <li key={m.id}>
                  <Badge className="font-mono">{m.name}</Badge>
                </li>
              ))}
            </ul>
          )}
        </section>
      </div>
      {publishing && <PublishVersionDialog priceBook={priceBook} onClose={() => setPublishing(false)} />}
    </DetailPage>
  );
}

/**
 * 价格表详情（详情页模板）：版本历史（每个版本的单价、生效时间、发布人）与用它计价的模型；主操作是发布新版本。新版本立即生效、
 * 不改已结算用量的成本。
 */
export function PriceBookDetailPage({ priceBookId }: PriceBookDetailPageProps) {
  const read = usePermission('model_read', '查看价格表');
  if (!read.allowed)
    return (
      <DetailPage title="价格表">
        <PermissionGate mode="explain" title="没有权限查看价格表" allowed={false} reason={read.reason} />
      </DetailPage>
    );
  return (
    <DataRegion
      errorTitle="无法打开这个价格表"
      skeleton={
        <DetailPage title="价格表" loading>
          <div className="flex flex-col gap-3 pt-6">
            {[0, 1, 2, 3].map((i) => (
              <Skeleton key={i} className="h-5 w-full max-w-160" />
            ))}
          </div>
        </DetailPage>
      }
    >
      <Loaded priceBookId={priceBookId} />
    </DataRegion>
  );
}
