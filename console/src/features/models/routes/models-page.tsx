import { useState } from 'react';

import { PermissionGate } from '@/components/domain/permission-gate';
import { ListPage } from '@/components/templates';
import { Button } from '@/components/ui/button';
import { PlusIcon } from '@/components/ui/glyphs';
import { Icon } from '@/components/ui/icon';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { usePermission } from '@/lib/access';

import { CatalogTable } from '../components/catalog-table';
import { ModelSheet } from '../components/model-sheet';
import { CreatePriceBookDialog } from '../components/price-book-dialogs';
import { ProviderDialog } from '../components/provider-dialog';
import { PriceBookTable } from '../components/price-book-table';
import { ProviderTable } from '../components/provider-table';

export type ModelsTab = 'providers' | 'catalog' | 'price-books';

export interface ModelsPageProps {
  tab: ModelsTab;
  onTabChange: (tab: ModelsTab) => void;
}

const CREATE_LABEL: Record<ModelsTab, string> = {
  providers: '新建供应商账号',
  catalog: '添加模型',
  'price-books': '新建价格表',
};

/**
 * 模型（列表页模板加标签页，ADR-0056）：供应商账号、模型目录、价格表，都属于租户。标签页在搜索参数里；页头的主操作随标签页变化。
 */
export function ModelsPage({ tab, onTabChange }: ModelsPageProps) {
  const read = usePermission('model_read', '查看模型目录');
  const write = usePermission('model_write', CREATE_LABEL[tab]);
  const [creating, setCreating] = useState<ModelsTab | null>(null);

  if (!read.allowed)
    return (
      <ListPage title="模型">
        <PermissionGate mode="explain" title="没有权限查看模型目录" allowed={false} reason={read.reason} />
      </ListPage>
    );

  const createButton = (
    <PermissionGate mode="disable" allowed={write.allowed} reason={write.reason}>
      <Button variant="primary" icon={<Icon glyph={PlusIcon} />} onClick={() => setCreating(tab)}>
        {CREATE_LABEL[tab]}
      </Button>
    </PermissionGate>
  );

  return (
    <Tabs value={tab} onValueChange={(v) => onTabChange(v as ModelsTab)} className="h-full">
      <ListPage
        title="模型"
        actions={createButton}
        tabs={
          <TabsList>
            <TabsTrigger value="providers">供应商账号</TabsTrigger>
            <TabsTrigger value="catalog">模型目录</TabsTrigger>
            <TabsTrigger value="price-books">价格表</TabsTrigger>
          </TabsList>
        }
      >
        <TabsContent value="providers" className="flex min-h-0 flex-1 flex-col">
          {tab === 'providers' && <ProviderTable createButton={createButton} />}
        </TabsContent>
        <TabsContent value="catalog" className="flex min-h-0 flex-1 flex-col">
          {tab === 'catalog' && <CatalogTable createButton={createButton} />}
        </TabsContent>
        <TabsContent value="price-books" className="flex min-h-0 flex-1 flex-col">
          {tab === 'price-books' && <PriceBookTable createButton={createButton} />}
        </TabsContent>
      </ListPage>
      {creating === 'providers' && <ProviderDialog open onOpenChange={() => setCreating(null)} />}
      {creating === 'catalog' && <ModelSheet open onOpenChange={() => setCreating(null)} />}
      <CreatePriceBookDialog
        open={creating === 'price-books'}
        onOpenChange={(open) => !open && setCreating(null)}
      />
    </Tabs>
  );
}
