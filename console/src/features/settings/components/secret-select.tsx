import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';

import { ResourceSelect, type ResourceSource } from '@/components/domain/resource-picker';
import { Button } from '@/components/ui/button';
import { PlusIcon } from '@/components/ui/glyphs';
import { Icon } from '@/components/ui/icon';
import { Input } from '@/components/ui/input';
import { usePermission, useProject } from '@/lib/access';

import { secretsQuery } from '../api/secrets';

import { CreateSecretDialog } from './secret-dialogs';

export interface SecretSelectProps {
  /** 触发器（或输入框）的 id，与 FormField 的 id 相同。 */
  id: string;
  value: string | null;
  onChange: (id: string | null) => void;
  /**
   * 谁来引用：`tenant` 是租户级的资源（供应商账号），只能选租户级凭据；`project` 是项目级的资源（MCP 服务），可以选租户级与
   * 当前项目的凭据。
   */
  usedBy: 'tenant' | 'project';
  /** 列表顶部的"不选"项（"不需要凭据"）；不给时必须选一个。 */
  nullOption?: string;
  disabled?: boolean;
  invalid?: boolean;
  describedBy?: string;
  ariaLabel?: string;
}

/**
 * 凭据的选择器：可搜索，只列出这个资源能引用的凭据；有写入权限时旁边是"写入凭据"，写好后直接选中。没有查看凭据的权限时
 * 退为输入凭据 ID（由租户管理员告知），不显示任何凭据的名称。
 */
export function SecretSelect({
  id,
  value,
  onChange,
  usedBy,
  nullOption,
  disabled,
  invalid,
  describedBy,
  ariaLabel,
}: SecretSelectProps) {
  const { tenantId, projectId } = useProject();
  const read = usePermission('secret_read', '查看凭据');
  const write = usePermission('secret_write', '写入凭据');
  const [creating, setCreating] = useState(false);
  const secrets = useQuery({ ...secretsQuery(tenantId), enabled: read.allowed });

  if (!read.allowed)
    return (
      <Input
        id={id}
        value={value ?? ''}
        disabled={disabled}
        placeholder="sec_…（向租户管理员索要凭据 ID）"
        className="font-mono"
        aria-label={ariaLabel}
        aria-describedby={describedBy}
        aria-invalid={invalid || undefined}
        onChange={(e) => onChange(e.target.value.trim() || null)}
      />
    );

  const usable = (secrets.data ?? []).filter(
    (s) => s.project_id === null || (usedBy === 'project' && s.project_id === projectId),
  );
  const source: ResourceSource = {
    options: usable.map((s) => ({
      id: s.id,
      name: s.name,
      detail: s.project_id === null ? '租户内通用' : '本项目',
    })),
    loading: secrets.isPending,
    error: secrets.error,
    onRetry: () => void secrets.refetch(),
  };

  return (
    <div className="flex items-center gap-2">
      <div className="min-w-0 flex-1">
        <ResourceSelect
          id={id}
          value={value}
          onChange={onChange}
          source={source}
          searchLabel="搜索凭据"
          emptyLabel={usedBy === 'tenant' ? '还没有租户级凭据' : '还没有本项目能用的凭据'}
          missingLabel="已删除或不能在这里使用"
          nullOption={nullOption}
          disabled={disabled}
          invalid={invalid}
          describedBy={describedBy}
          ariaLabel={ariaLabel}
        />
      </div>
      {write.allowed && !disabled && (
        <Button size="md" variant="ghost" icon={<Icon glyph={PlusIcon} />} onClick={() => setCreating(true)}>
          写入凭据
        </Button>
      )}
      <CreateSecretDialog
        open={creating}
        onOpenChange={setCreating}
        defaultScope={usedBy === 'tenant' ? 'tenant' : 'project'}
        lockScope={usedBy === 'tenant'}
        onCreated={(secret) => onChange(secret.id)}
      />
    </div>
  );
}
