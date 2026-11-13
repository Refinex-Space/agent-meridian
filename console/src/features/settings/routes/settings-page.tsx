import { useState } from 'react';

import { PermissionGate } from '@/components/domain/permission-gate';
import { ListPage } from '@/components/templates';
import { Button } from '@/components/ui/button';
import { PlusIcon } from '@/components/ui/glyphs';
import { Icon } from '@/components/ui/icon';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { usePermission } from '@/lib/access';

import { CreateSecretDialog } from '../components/secret-dialogs';
import { SecretTable } from '../components/secret-table';

/** 设置的标签页：T23 有凭据，T25b 加成员与角色、API Key、通知地址、策略、租户设置与审计日志。 */
export type SettingsTab = 'secrets';

export interface SettingsPageProps {
  tab: SettingsTab;
  onTabChange: (tab: SettingsTab) => void;
}

/**
 * 设置（列表页模板加标签页，与能力、模型一致）：标签页在搜索参数里；页头的主操作随标签页变化。
 */
export function SettingsPage({ tab, onTabChange }: SettingsPageProps) {
  const write = usePermission('secret_write', '写入凭据');
  const [creating, setCreating] = useState(false);
  const createButton = (
    <PermissionGate mode="disable" allowed={write.allowed} reason={write.reason}>
      <Button variant="primary" icon={<Icon glyph={PlusIcon} />} onClick={() => setCreating(true)}>
        写入凭据
      </Button>
    </PermissionGate>
  );
  return (
    <Tabs value={tab} onValueChange={(v) => onTabChange(v as SettingsTab)} className="h-full">
      <ListPage
        title="设置"
        actions={tab === 'secrets' ? createButton : undefined}
        tabs={
          <TabsList>
            <TabsTrigger value="secrets">凭据</TabsTrigger>
          </TabsList>
        }
      >
        <TabsContent value="secrets" className="flex min-h-0 flex-1 flex-col">
          {tab === 'secrets' && <SecretTable createButton={createButton} />}
        </TabsContent>
      </ListPage>
      <CreateSecretDialog open={creating} onOpenChange={setCreating} />
    </Tabs>
  );
}
