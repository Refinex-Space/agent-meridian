import { EmptyState } from '@/components/domain/empty-state';
import { DetailPage } from '@/components/templates';

/**
 * 设置（T25b）：成员与角色、项目、API Key、通知地址、凭据、策略、租户设置、审计日志。
 * 页面内容由对应的页面任务实现，现在是首次使用的空态。
 */
export function SettingsPage() {
  return (
    <DetailPage title="设置">
      <EmptyState variant="first-use" title="成员与角色、API Key、通知地址、凭据与审计日志会出现在这里" />
    </DetailPage>
  );
}
