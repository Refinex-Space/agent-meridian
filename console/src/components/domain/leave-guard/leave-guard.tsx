import { useBlocker } from '@tanstack/react-router';

import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog';

export interface LeaveGuardProps {
  /** 有未保存的更改：离开（站内跳转、关闭或刷新页面）前提示。 */
  when: boolean;
}

/**
 * 未保存就离开时的提示（frontend-design.md「交互规范·表单」）：站内跳转弹出确认对话框，留在本页或放弃更改离开；
 * 关闭、刷新页面由浏览器的离开提示接管。保存之后（`when` 为 false）不再拦截。
 */
export function LeaveGuard({ when }: LeaveGuardProps) {
  const blocker = useBlocker({
    shouldBlockFn: () => when,
    enableBeforeUnload: () => when,
    withResolver: true,
  });
  return (
    <AlertDialog open={blocker.status === 'blocked'} onOpenChange={(open) => !open && blocker.reset?.()}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>离开这个页面</AlertDialogTitle>
          <AlertDialogDescription>还有未保存的更改，离开后这些更改会丢失。</AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel onClick={() => blocker.reset?.()}>留在本页</AlertDialogCancel>
          <AlertDialogAction variant="danger" onClick={() => blocker.proceed?.()}>
            放弃更改并离开
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}
