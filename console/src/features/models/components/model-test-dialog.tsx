import { ErrorState } from '@/components/domain/error-state';
import { KeyValueList } from '@/components/domain/key-value-list';
import { StatusBadge } from '@/components/domain/status-badge';
import { Button } from '@/components/ui/button';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { useProject } from '@/lib/access';
import type { CatalogModel } from '@/lib/api/types';
import { formatDuration, formatInteger } from '@/lib/format';

import { TEST_MAX_TOKENS, TEST_PROMPT, useTestModel } from '../api/queries';

export interface ModelTestDialogProps {
  model: CatalogModel | null;
  onClose: () => void;
}

/**
 * 测试一个模型：确认后经直通接口发一次最小请求（ADR-0056），结果就在对话框里：延迟、回复与用量。会产生少量费用，计入当前项目，
 * 所以先说明再发送。
 */
export function ModelTestDialog({ model, onClose }: ModelTestDialogProps) {
  const { projectName } = useProject();
  const test = useTestModel();
  const result = test.data;
  const close = () => {
    if (test.isPending) return;
    test.reset();
    onClose();
  };
  const request =
    model?.kind === 'embedding'
      ? `把"${TEST_PROMPT}"转成向量`
      : `发送"${TEST_PROMPT}"，至多 ${TEST_MAX_TOKENS} 个输出 token`;

  return (
    <Dialog open={model !== null} onOpenChange={(open) => !open && close()}>
      <DialogContent size="md">
        <DialogHeader>
          <DialogTitle>测试 {model?.name}</DialogTitle>
          <DialogDescription>
            {request}。会产生少量费用，计入{projectName}的用量。
          </DialogDescription>
        </DialogHeader>
        {result && (
          <div className="flex flex-col gap-3" aria-live="polite">
            <StatusBadge of="custom" kind="success" label="调用成功" />
            <KeyValueList
              items={[
                { label: '延迟', value: formatDuration(result.latencyMs) },
                ...(model?.kind === 'embedding' ? [] : [{ label: '回复', value: result.reply || '（空）' }]),
                {
                  label: '用量',
                  value:
                    model?.kind === 'embedding'
                      ? `输入 ${formatInteger(result.inputTokens)} token`
                      : `输入 ${formatInteger(result.inputTokens)}、输出 ${formatInteger(result.outputTokens)} token`,
                },
              ]}
            />
          </div>
        )}
        {test.error ? <ErrorState size="inline" title="调用失败" error={test.error} /> : null}
        <DialogFooter>
          <Button disabled={test.isPending} onClick={close}>
            {result ? '完成' : '取消'}
          </Button>
          <Button variant="primary" pending={test.isPending} onClick={() => model && test.mutate(model)}>
            {result || test.error ? '再测一次' : '发送测试请求'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
