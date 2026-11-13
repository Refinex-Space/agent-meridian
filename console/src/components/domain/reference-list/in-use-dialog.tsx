import { Button } from '@/components/ui/button';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import type { ResourceReference } from '@/lib/api/types';

import { ReferenceList } from './reference-list';

export interface InUseDialogProps {
  /** 被引用的资源名；为空时对话框关闭。 */
  name: string | null;
  references: readonly ResourceReference[];
  /** 下一步怎么做（"先让下面的资源改用其他凭据，或删除它们"）。 */
  nextStep: string;
  onClose: () => void;
}

/**
 * 删除被引用的资源时的说明：不能删除的原因与引用方清单，没有确认按钮。删除前已知有引用方时直接打开它，不让用户先输入名称再被拒绝。
 */
export function InUseDialog({ name, references, nextStep, onClose }: InUseDialogProps) {
  return (
    <Dialog open={name !== null} onOpenChange={(open) => !open && onClose()}>
      <DialogContent size="md">
        <DialogHeader>
          <DialogTitle>{name} 仍被引用，不能删除</DialogTitle>
          <DialogDescription>{nextStep}</DialogDescription>
        </DialogHeader>
        <ReferenceList references={references} label={`引用 ${name ?? ''} 的资源`} />
        <DialogFooter>
          <Button variant="primary" onClick={onClose}>
            知道了
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
