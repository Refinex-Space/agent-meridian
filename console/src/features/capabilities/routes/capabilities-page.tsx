import { EmptyState } from '@/components/domain/empty-state';
import { DetailPage } from '@/components/templates';

/**
 * 能力（T23）：技能、MCP 服务、自定义工具的说明。
 * 页面内容由对应的页面任务实现，现在是首次使用的空态。
 */
export function CapabilitiesPage() {
  return (
    <DetailPage title="能力">
      <EmptyState variant="first-use" title="本项目的技能与 MCP 服务会出现在这里" />
    </DetailPage>
  );
}
