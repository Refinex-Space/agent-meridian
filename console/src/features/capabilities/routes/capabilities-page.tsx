import { useState } from 'react';

import { PermissionGate } from '@/components/domain/permission-gate';
import { ListPage } from '@/components/templates';
import { Button } from '@/components/ui/button';
import { PlusIcon } from '@/components/ui/glyphs';
import { Icon } from '@/components/ui/icon';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { usePermission } from '@/lib/access';

import { CustomToolsGuide } from '../components/custom-tools-guide';
import { McpServerDialog } from '../components/mcp-server-dialog';
import { McpTable } from '../components/mcp-table';
import { SkillTable } from '../components/skill-table';
import { SkillUploadDialog } from '../components/skill-upload-dialog';

export type CapabilitiesTab = 'skills' | 'mcp-servers' | 'custom-tools';
export const CAPABILITIES_TABS: readonly CapabilitiesTab[] = ['skills', 'mcp-servers', 'custom-tools'];

export interface CapabilitiesPageProps {
  tab: CapabilitiesTab;
  onTabChange: (tab: CapabilitiesTab) => void;
}

/**
 * 能力（列表页模板加标签页，ADR-0056）：技能、MCP 服务与自定义工具的说明。标签页在搜索参数里，刷新与分享链接停在同一页；
 * 页头的主操作随标签页变化（上传技能、登记 MCP 服务）。
 */
export function CapabilitiesPage({ tab, onTabChange }: CapabilitiesPageProps) {
  const read = usePermission('agent_read', '查看技能与 MCP 服务');
  const write = usePermission('agent_write', tab === 'skills' ? '上传技能' : '登记 MCP 服务');
  const [uploading, setUploading] = useState(false);
  const [registering, setRegistering] = useState(false);

  if (!read.allowed)
    return (
      <ListPage title="能力">
        <PermissionGate mode="explain" title="没有权限查看能力" allowed={false} reason={read.reason} />
      </ListPage>
    );

  const uploadButton = (
    <PermissionGate mode="disable" allowed={write.allowed} reason={write.reason}>
      <Button variant="primary" icon={<Icon glyph={PlusIcon} />} onClick={() => setUploading(true)}>
        上传技能
      </Button>
    </PermissionGate>
  );
  const registerButton = (
    <PermissionGate mode="disable" allowed={write.allowed} reason={write.reason}>
      <Button variant="primary" icon={<Icon glyph={PlusIcon} />} onClick={() => setRegistering(true)}>
        登记 MCP 服务
      </Button>
    </PermissionGate>
  );

  return (
    <Tabs value={tab} onValueChange={(v) => onTabChange(v as CapabilitiesTab)} className="h-full">
      <ListPage
        title="能力"
        actions={tab === 'skills' ? uploadButton : tab === 'mcp-servers' ? registerButton : undefined}
        tabs={
          <TabsList>
            <TabsTrigger value="skills">技能</TabsTrigger>
            <TabsTrigger value="mcp-servers">MCP 服务</TabsTrigger>
            <TabsTrigger value="custom-tools">自定义工具</TabsTrigger>
          </TabsList>
        }
      >
        <TabsContent value="skills" className="flex min-h-0 flex-1 flex-col">
          {tab === 'skills' && <SkillTable uploadButton={uploadButton} />}
        </TabsContent>
        <TabsContent value="mcp-servers" className="flex min-h-0 flex-1 flex-col">
          {tab === 'mcp-servers' && <McpTable registerButton={registerButton} />}
        </TabsContent>
        <TabsContent value="custom-tools" className="flex min-h-0 flex-1 flex-col">
          {tab === 'custom-tools' && <CustomToolsGuide />}
        </TabsContent>
      </ListPage>
      <SkillUploadDialog open={uploading} onOpenChange={setUploading} />
      <McpServerDialog open={registering} onOpenChange={setRegistering} />
    </Tabs>
  );
}
