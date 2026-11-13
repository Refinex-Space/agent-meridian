import { useState } from 'react';

import { fieldAria } from '@/components/domain/form-field';
import { StatusGlyph } from '@/components/domain/status-badge';
import { Button } from '@/components/ui/button';
import { PlusIcon, TrashIcon } from '@/components/ui/glyphs';
import { Icon } from '@/components/ui/icon';
import { Input } from '@/components/ui/input';
import { cn } from '@/lib/cn';

import { checkDomain, MAX_ALLOWLIST, splitDomains } from '../model/environment';

export interface AllowlistEditorProps {
  /** 新增输入框的 id（字段的标签指向它）。 */
  id: string;
  value: readonly string[];
  onChange: (next: string[]) => void;
  /** 已有条目的错误（下标 → 说明），来自整表校验或服务端。 */
  itemErrors: Readonly<Record<number, string>>;
  readOnly?: boolean;
}

/**
 * 出网白名单：已有的域名一行一条，可移除；下方输入新域名，回车或"添加"加入，添加前就地校验（与 control-plane 相同的规则），
 * 粘贴多个域名（空白、逗号或换行分隔）一次加入。
 */
export function AllowlistEditor({ id, value, onChange, itemErrors, readOnly = false }: AllowlistEditorProps) {
  const [draft, setDraft] = useState('');
  const [error, setError] = useState<string | null>(null);

  const add = () => {
    const domains = splitDomains(draft);
    if (domains.length === 0) {
      setError('写一个域名，例如 api.example.com。');
      return;
    }
    for (const domain of domains) {
      const problem = checkDomain(domain);
      if (problem) {
        setError(`${domain}：${problem}`);
        return;
      }
    }
    const fresh = domains.filter((d, i) => !value.includes(d) && domains.indexOf(d) === i);
    if (fresh.length === 0) {
      setError('这些域名已经在白名单里。');
      return;
    }
    if (value.length + fresh.length > MAX_ALLOWLIST) {
      setError(`白名单至多 ${MAX_ALLOWLIST} 个域名，还能加 ${MAX_ALLOWLIST - value.length} 个。`);
      return;
    }
    onChange([...value, ...fresh]);
    setDraft('');
    setError(null);
  };

  return (
    <div className="flex flex-col gap-2">
      <ul aria-label="出网白名单" className="flex flex-col border-t border-rule">
        {value.length === 0 && (
          <li className="flex h-10 items-center border-b border-rule text-13 text-ink-2">
            没有允许的域名：沙箱不能访问外网
          </li>
        )}
        {value.map((domain, i) => (
          <li key={domain} className="flex min-h-10 flex-col justify-center border-b border-rule py-1">
            <div className="flex items-center gap-3">
              {itemErrors[i] && <StatusGlyph kind="failure" />}
              <span
                className={cn('min-w-0 flex-1 truncate font-mono text-12', itemErrors[i] && 'text-danger')}
              >
                {domain}
              </span>
              {!readOnly && (
                <Button
                  size="icon-sm"
                  variant="ghost"
                  aria-label={`移除 ${domain}`}
                  onClick={() => onChange(value.filter((d) => d !== domain))}
                >
                  <Icon glyph={TrashIcon} />
                </Button>
              )}
            </div>
            {itemErrors[i] && <p className="text-12 text-danger">{itemErrors[i]}</p>}
          </li>
        ))}
      </ul>
      {!readOnly && (
        <div className="flex flex-col gap-2">
          <div className="flex items-center gap-2">
            <Input
              id={id}
              value={draft}
              placeholder="api.example.com"
              className="max-w-80 font-mono text-12"
              onChange={(e) => {
                setDraft(e.target.value);
                setError(null);
              }}
              onKeyDown={(e) => {
                if (e.key === 'Enter') {
                  e.preventDefault();
                  add();
                }
              }}
              {...fieldAria(id, error)}
            />
            <Button icon={<Icon glyph={PlusIcon} />} onClick={add}>
              添加
            </Button>
            <span className="ml-auto text-12 text-ink-2 tabular-nums">
              {value.length} / {MAX_ALLOWLIST}
            </span>
          </div>
          {error && (
            <p id={`${id}-error`} className="text-12 text-danger">
              {error}
            </p>
          )}
        </div>
      )}
    </div>
  );
}
