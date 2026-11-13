import { useId, useState, type ReactNode } from 'react';

import {
  AlertDialog,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';

import { ErrorState } from '../error-state';

export interface DangerConfirmDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** 写清对象与动作，例如"删除智能体 经营分析助手"。 */
  title: string;
  /** 后果：会失去什么、影响到谁（"删除后，该智能体的 12 个会话将无法继续"），不写"确定吗"。 */
  consequences: ReactNode;
  /** 确认按钮的动作，例如"删除智能体""吊销 API Key"。 */
  confirmLabel: string;
  /** 最高风险的操作要求输入对象的名称才能确认（删除租户、吊销 API Key 等）。 */
  confirmName?: string;
  /** 执行操作；返回的 Promise 失败时对话框保持打开并就地显示错误，成功后关闭。 */
  onConfirm: () => Promise<void> | void;
}

/**
 * 不可逆操作的确认：写明后果；需要时要求输入名称；确认中禁止重复提交与关闭，失败就地显示原因与请求 ID。
 */
export function DangerConfirmDialog({
  open,
  onOpenChange,
  title,
  consequences,
  confirmLabel,
  confirmName,
  onConfirm,
}: DangerConfirmDialogProps) {
  const inputId = useId();
  const [typed, setTyped] = useState('');
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const matches = confirmName === undefined || typed === confirmName;

  const reset = () => {
    setTyped('');
    setError(null);
  };

  const handleOpenChange = (next: boolean) => {
    if (pending) return;
    if (!next) reset();
    onOpenChange(next);
  };

  const confirm = async () => {
    if (!matches || pending) return;
    setPending(true);
    setError(null);
    try {
      await onConfirm();
      reset();
      onOpenChange(false);
    } catch (e) {
      setError(e);
    } finally {
      setPending(false);
    }
  };

  return (
    <AlertDialog open={open} onOpenChange={handleOpenChange}>
      <AlertDialogContent
        onEscapeKeyDown={(e) => pending && e.preventDefault()}
        onOpenAutoFocus={(e) => {
          // 要求输入名称时聚焦到输入框；否则 Radix 默认聚焦"取消"，回车不会误触危险操作。
          if (confirmName === undefined) return;
          e.preventDefault();
          document.getElementById(inputId)?.focus();
        }}
      >
        <AlertDialogHeader>
          <AlertDialogTitle>{title}</AlertDialogTitle>
          <AlertDialogDescription asChild>
            <div className="flex flex-col gap-2">{consequences}</div>
          </AlertDialogDescription>
        </AlertDialogHeader>
        {confirmName !== undefined && (
          <form
            className="flex flex-col gap-2"
            onSubmit={(e) => {
              e.preventDefault();
              void confirm();
            }}
          >
            <label htmlFor={inputId} className="text-13 text-ink">
              输入 <code className="rounded-control bg-surface-inset px-1 text-12">{confirmName}</code> 以确认
            </label>
            <Input
              id={inputId}
              value={typed}
              autoComplete="off"
              spellCheck={false}
              disabled={pending}
              onChange={(e) => setTyped(e.target.value)}
            />
          </form>
        )}
        {error !== null && <ErrorState error={error} size="inline" title={`没有完成：${confirmLabel}`} />}
        <AlertDialogFooter>
          <AlertDialogCancel disabled={pending}>取消</AlertDialogCancel>
          <Button variant="danger" disabled={!matches} pending={pending} onClick={() => void confirm()}>
            {confirmLabel}
          </Button>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}
