import { useState } from 'react';

import { CopyableId } from '@/components/domain/copyable-id';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { PlusIcon, TrashIcon } from '@/components/ui/glyphs';
import { Icon } from '@/components/ui/icon';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { RadioGroup, RadioGroupItem } from '@/components/ui/radio-group';
import { Textarea } from '@/components/ui/textarea';
import { cn } from '@/lib/cn';

import {
  SIZES,
  type EnvironmentForm,
  type EnvironmentMeta,
  type SandboxSize,
  type Tier,
} from '../fixtures/environment';
import { Field, fieldAria } from '../shared/field';
import { EditPage, EditSection } from '../templates/edit-page';

const DOMAIN = /^(\*\.)?([a-z0-9-]+\.)+[a-z]{2,}$/;

export interface EnvironmentEditProps {
  environment: EnvironmentForm;
  meta: EnvironmentMeta;
}

/**
 * 环境编辑（编辑页模板的第二个例子）：执行档位、沙箱的资源规格与出网白名单。保存后修订号加一，保存栏写明对进行中会话的影响。
 */
export function EnvironmentEdit({ environment, meta }: EnvironmentEditProps) {
  const [baseline, setBaseline] = useState(environment);
  const [form, setForm] = useState(environment);
  const [revision, setRevision] = useState(meta.revision);
  const [savedAt, setSavedAt] = useState(meta.savedAt);
  const [saving, setSaving] = useState(false);
  const [draftDomain, setDraftDomain] = useState('');
  const [domainError, setDomainError] = useState<string | null>(null);
  const dirty = JSON.stringify(form) !== JSON.stringify(baseline);
  const patch = (p: Partial<EnvironmentForm>) => setForm((f) => ({ ...f, ...p }));

  const addDomain = () => {
    const d = draftDomain.trim().toLowerCase();
    if (!DOMAIN.test(d)) {
      setDomainError('写域名，例如 api.example.com；子域通配写成 *.example.com。');
      return;
    }
    if (form.egressAllowlist.includes(d)) {
      setDomainError('这个域名已经在白名单里。');
      return;
    }
    patch({ egressAllowlist: [...form.egressAllowlist, d] });
    setDraftDomain('');
    setDomainError(null);
  };

  return (
    <EditPage
      title={`编辑 ${baseline.name}`}
      meta={
        <>
          <Badge>修订 {revision}</Badge>
          <span>
            {meta.agents} 个智能体、{meta.activeSessions} 个进行中的会话在用
          </span>
          <CopyableId id={meta.id} label="环境 ID" />
        </>
      }
      groups={[
        { id: 'env-basic', label: '基本信息' },
        { id: 'env-tier', label: '执行档位' },
        { id: 'env-egress', label: '出网', invalid: domainError !== null },
      ]}
      saveBar={{
        dirty,
        saving,
        savedAt,
        saveLabel: `保存为修订 ${revision + 1}`,
        onSave: () => {
          setSaving(true);
          window.setTimeout(() => {
            setBaseline(form);
            setRevision((r) => r + 1);
            setSavedAt(new Date().toISOString());
            setSaving(false);
          }, 600);
        },
        onDiscard: () => setForm(baseline),
      }}
    >
      <EditSection id="env-basic" title="基本信息">
        <Field id="env-name" label="名称" className="max-w-100">
          <Input id="env-name" value={form.name} onChange={(e) => patch({ name: e.target.value })} />
        </Field>
        <Field id="env-description" label="说明">
          <Textarea
            id="env-description"
            rows={2}
            value={form.description}
            onChange={(e) => patch({ description: e.target.value })}
          />
        </Field>
      </EditSection>

      <EditSection id="env-tier" title="执行档位">
        <RadioGroup
          value={form.tier}
          onValueChange={(v) => patch({ tier: v as Tier })}
          aria-label="执行档位"
          className="gap-3"
        >
          {(
            [
              ['none', '无沙箱', '只有文件工具，文件存在平台的工作区里；不能执行命令'],
              ['sandbox', '沙箱', '每个会话一个隔离的沙箱，可以执行命令；按资源规格计量'],
            ] as const
          ).map(([value, label, detail]) => (
            <div key={value} className="flex items-start gap-2">
              <span className="flex h-5 items-center">
                <RadioGroupItem id={`tier-${value}`} value={value} />
              </span>
              <Label htmlFor={`tier-${value}`} className="flex-col items-start gap-0 font-normal">
                <span className="font-medium">{label}</span>
                <span className="text-ink-2">{detail}</span>
              </Label>
            </div>
          ))}
        </RadioGroup>
        {form.tier === 'sandbox' && (
          <Field id="env-size" label="资源规格" group>
            <RadioGroup
              value={form.size}
              onValueChange={(v) => patch({ size: v as SandboxSize })}
              aria-labelledby="env-size-label"
              className="grid grid-cols-3 gap-0 border-y border-rule"
            >
              {SIZES.map((size, i) => (
                <Label
                  key={size.value}
                  htmlFor={`size-${size.value}`}
                  className={cn(
                    'flex cursor-pointer items-start gap-2 px-4 py-3 font-normal hover:bg-surface-hover',
                    i > 0 && 'border-l border-rule',
                  )}
                >
                  <span className="flex h-5 items-center">
                    <RadioGroupItem id={`size-${size.value}`} value={size.value} />
                  </span>
                  <span className="flex flex-col gap-1">
                    <span className="font-medium">{size.label}</span>
                    <span className="text-12 text-ink-2 tabular-nums">
                      {size.cpu}，内存 {size.memory}，磁盘 {size.disk}
                    </span>
                  </span>
                </Label>
              ))}
            </RadioGroup>
          </Field>
        )}
      </EditSection>

      <EditSection id="env-egress" title="出网">
        <Field
          id="env-domain"
          label="允许访问的域名"
          hint="沙箱只能访问白名单里的域名，其余一律拒绝；子域通配写成 *.example.com"
          error={domainError}
        >
          <ul aria-label="出网白名单" className="flex flex-col border-t border-rule">
            {form.egressAllowlist.map((domain) => (
              <li key={domain} className="flex h-10 items-center gap-3 border-b border-rule">
                <span className="min-w-0 flex-1 truncate font-mono text-12">{domain}</span>
                <Button
                  size="icon-sm"
                  variant="ghost"
                  aria-label={`移除 ${domain}`}
                  onClick={() => patch({ egressAllowlist: form.egressAllowlist.filter((d) => d !== domain) })}
                >
                  <Icon glyph={TrashIcon} />
                </Button>
              </li>
            ))}
          </ul>
          <form
            className="flex gap-2"
            onSubmit={(e) => {
              e.preventDefault();
              addDomain();
            }}
          >
            <Input
              id="env-domain"
              value={draftDomain}
              placeholder="api.example.com"
              className="max-w-80 font-mono text-12"
              onChange={(e) => setDraftDomain(e.target.value)}
              {...fieldAria('env-domain', domainError)}
            />
            <Button type="submit" icon={<Icon glyph={PlusIcon} />}>
              添加
            </Button>
          </form>
        </Field>
      </EditSection>
    </EditPage>
  );
}
