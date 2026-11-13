import { useState } from 'react';

import { ErrorState } from '@/components/domain/error-state';
import { FormField, fieldAria } from '@/components/domain/form-field';
import { Button } from '@/components/ui/button';
import {
  Dialog,
  DialogBody,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { ApiError } from '@/lib/api/errors';
import type { PriceBook, TokenPrices } from '@/lib/api/types';

import { useCreatePriceBook, usePublishPriceBookVersion } from '../api/queries';
import { PRICE, PRICE_FIELDS, ZERO_PRICES } from '../model/catalog';

function priceErrors(prices: TokenPrices): Record<string, string> {
  const errors: Record<string, string> = {};
  for (const { key } of PRICE_FIELDS)
    if (!PRICE.test(prices[key].trim())) errors[`prices.${key}`] = '写非负的十进制数，至多 6 位小数。';
  return errors;
}

const trimmed = (prices: TokenPrices): TokenPrices =>
  Object.fromEntries(PRICE_FIELDS.map(({ key }) => [key, prices[key].trim()])) as unknown as TokenPrices;

/** 五个单价（每百万 token），两列排列；币种写在标签里。 */
function PriceFields({
  prices,
  currency,
  errors,
  onChange,
}: {
  prices: TokenPrices;
  currency: string;
  errors: Record<string, string>;
  onChange: (prices: TokenPrices) => void;
}) {
  return (
    <div className="grid grid-cols-2 gap-4">
      {PRICE_FIELDS.map(({ key, label }) => {
        const id = `price-${key}`;
        const error = errors[`prices.${key}`];
        return (
          <FormField key={key} id={id} label={`${label}（${currency || '币种'} / 百万 token）`} error={error}>
            <Input
              id={id}
              inputMode="decimal"
              value={prices[key]}
              className="tabular-nums"
              onChange={(e) => onChange({ ...prices, [key]: e.target.value })}
              {...fieldAria(id, error)}
            />
          </FormField>
        );
      })}
    </div>
  );
}

export interface CreatePriceBookDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

/** 新建价格表：名称、币种与五个单价，同时发布版本 1、立即生效。 */
export function CreatePriceBookDialog({ open, onOpenChange }: CreatePriceBookDialogProps) {
  const create = useCreatePriceBook();
  const [name, setName] = useState('');
  const [currency, setCurrency] = useState('CNY');
  const [prices, setPrices] = useState<TokenPrices>(ZERO_PRICES);
  const [submitted, setSubmitted] = useState(false);

  const errors: Record<string, string> = submitted
    ? {
        ...priceErrors(prices),
        ...(!name.trim() ? { name: '写一个名称，模型按它选择价格表。' } : {}),
        ...(!/^[A-Z]{3}$/.test(currency) ? { currency: '写三个大写字母的币种代码，例如 CNY。' } : {}),
      }
    : {};
  const nameTaken = create.error instanceof ApiError && create.error.problem?.code === 'catalog.name_taken';
  if (nameTaken) errors['name'] = '租户内已有同名的价格表，换一个名字。';

  const close = (next: boolean) => {
    if (create.isPending) return;
    if (!next) {
      setName('');
      setCurrency('CNY');
      setPrices(ZERO_PRICES);
      setSubmitted(false);
      create.reset();
    }
    onOpenChange(next);
  };

  const submit = () => {
    setSubmitted(true);
    create.reset();
    if (!name.trim() || !/^[A-Z]{3}$/.test(currency) || Object.keys(priceErrors(prices)).length > 0) return;
    create.mutate(
      { name: name.trim(), currency, prices: trimmed(prices) },
      { onSuccess: () => close(false) },
    );
  };

  return (
    <Dialog open={open} onOpenChange={close}>
      <DialogContent
        size="lg"
        onOpenAutoFocus={(e) => {
          e.preventDefault();
          document.getElementById('price-book-name')?.focus();
        }}
      >
        <DialogHeader>
          <DialogTitle>新建价格表</DialogTitle>
          <DialogDescription>保存即发布版本 1；之后改价发布新版本，已结算的用量不受影响。</DialogDescription>
        </DialogHeader>
        <DialogBody label="价格表">
          <form
            id="price-book-form"
            className="flex flex-col gap-4"
            onSubmit={(e) => {
              e.preventDefault();
              submit();
            }}
          >
            <div className="grid grid-cols-2 gap-4">
              <FormField id="price-book-name" label="名称" error={errors['name']}>
                <Input
                  id="price-book-name"
                  value={name}
                  maxLength={128}
                  placeholder="通义千问 Plus"
                  autoComplete="off"
                  onChange={(e) => {
                    setName(e.target.value);
                    if (nameTaken) create.reset();
                  }}
                  {...fieldAria('price-book-name', errors['name'])}
                />
              </FormField>
              <FormField
                id="price-book-currency"
                label="币种"
                hint="ISO 4217 代码，创建后不能改"
                error={errors['currency']}
              >
                <Input
                  id="price-book-currency"
                  value={currency}
                  maxLength={3}
                  className="font-mono"
                  onChange={(e) => setCurrency(e.target.value.toUpperCase())}
                  {...fieldAria('price-book-currency', errors['currency'])}
                />
              </FormField>
            </div>
            <PriceFields prices={prices} currency={currency} errors={errors} onChange={setPrices} />
            {create.error && !nameTaken ? (
              <ErrorState size="inline" title="保存失败" error={create.error} />
            ) : null}
          </form>
        </DialogBody>
        <DialogFooter>
          <Button disabled={create.isPending} onClick={() => close(false)}>
            取消
          </Button>
          <Button type="submit" form="price-book-form" variant="primary" pending={create.isPending}>
            创建并发布 v1
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

export interface PublishVersionDialogProps {
  priceBook: PriceBook | null;
  onClose: () => void;
}

/**
 * 发布价格表的新版本：从当前版本的单价改起。新版本立即生效，之后结算的用量按新价；已有的用量记录仍按记录时的版本，不重新计价。
 */
export function PublishVersionDialog({ priceBook, onClose }: PublishVersionDialogProps) {
  const publish = usePublishPriceBookVersion();
  const [prices, setPrices] = useState<TokenPrices>(priceBook?.current_version.prices ?? ZERO_PRICES);
  const [submitted, setSubmitted] = useState(false);
  const errors = submitted ? priceErrors(prices) : {};
  const next = (priceBook?.current_version.version ?? 0) + 1;

  const close = () => {
    if (publish.isPending) return;
    publish.reset();
    onClose();
  };

  const submit = () => {
    setSubmitted(true);
    if (!priceBook || Object.keys(priceErrors(prices)).length > 0) return;
    publish.mutate({ id: priceBook.id, prices: trimmed(prices) }, { onSuccess: close });
  };

  return (
    <Dialog open={priceBook !== null} onOpenChange={(open) => !open && close()}>
      <DialogContent size="lg">
        <DialogHeader>
          <DialogTitle>
            发布 {priceBook?.name} v{next}
          </DialogTitle>
          <DialogDescription>
            发布后立即生效：之后结算的用量按新价；已经结算的用量仍按当时的版本，成本不变。版本发布后不能修改。
          </DialogDescription>
        </DialogHeader>
        <DialogBody label="新版本的单价">
          <form
            id="publish-version-form"
            className="flex flex-col gap-4"
            onSubmit={(e) => {
              e.preventDefault();
              submit();
            }}
          >
            <PriceFields
              prices={prices}
              currency={priceBook?.currency ?? ''}
              errors={errors}
              onChange={setPrices}
            />
            {publish.error ? <ErrorState size="inline" title="发布失败" error={publish.error} /> : null}
          </form>
        </DialogBody>
        <DialogFooter>
          <Button disabled={publish.isPending} onClick={close}>
            取消
          </Button>
          <Button type="submit" form="publish-version-form" variant="primary" pending={publish.isPending}>
            发布 v{next}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
