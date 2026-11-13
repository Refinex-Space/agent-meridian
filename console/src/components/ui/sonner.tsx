/*
 * 来源：shadcn/ui new-york-v4 `sonner`（shadcn CLI 4.23.0 生成，2026-11-13）。与上游的差异：
 * - 去掉 next-themes：通知渲染在 <html data-theme> 之内，颜色直接取令牌，跟随主题无需额外设置；
 * - 用 `unstyled` 完全接管外观（上游只换几个 CSS 变量）：surface-raised 底、1px 刻线、8px 圆角、唯一的浮层阴影，
 *   标题 13px 500 字重、描述 12px 石墨色；错误类左侧信号红图标；
 * - 通知策略写进默认值（frontend-design.md「通知策略」）：同时最多 3 条、成功类 4 秒消失；错误类用 `notify.error`，
 *   不自动消失、带关闭按钮；
 * - 进出场时长压到 200ms（Sonner 默认 400ms），减少动效时由 Sonner 自身关闭位移；图标换成 Phosphor 的封装。
 */
import type { ReactNode } from 'react';
import { Toaster as Sonner, toast, type ExternalToast, type ToasterProps } from 'sonner';

import { cn } from '@/lib/cn';

import { CheckCircleIcon, CircleNotchIcon, InfoIcon, WarningCircleIcon, WarningIcon, XIcon } from './glyphs';
import { Icon } from './icon';

/** 通知的容器，放在应用根部一次。 */
export function Toaster({ toastOptions, ...props }: ToasterProps) {
  return (
    <Sonner
      position="bottom-right"
      visibleToasts={3}
      duration={4000}
      gap={8}
      containerAriaLabel="通知"
      icons={{
        success: <Icon glyph={CheckCircleIcon} className="text-success" />,
        info: <Icon glyph={InfoIcon} className="text-ink-2" />,
        warning: <Icon glyph={WarningIcon} className="text-brass-ink" />,
        error: <Icon glyph={WarningCircleIcon} className="text-danger" />,
        loading: <Icon glyph={CircleNotchIcon} className="text-ink-2 motion-safe:animate-spin" />,
        close: <Icon glyph={XIcon} />,
      }}
      toastOptions={{
        unstyled: true,
        ...toastOptions,
        classNames: {
          toast: cn(
            'shadow-overlay flex w-(--width) items-start gap-3 rounded-overlay border border-rule bg-surface-raised p-3 text-13 text-ink',
            'duration-200!',
          ),
          icon: 'flex h-5 shrink-0 items-center',
          content: 'flex min-w-0 flex-1 flex-col gap-1',
          title: 'leading-5 font-medium',
          description: 'text-12 text-ink-2',
          actionButton:
            'h-7 shrink-0 rounded-control px-2 text-13 font-medium text-accent transition-colors duration-150 hover:bg-surface-hover',
          cancelButton:
            'h-7 shrink-0 rounded-control px-2 text-13 text-ink-2 transition-colors duration-150 hover:bg-surface-hover hover:text-ink',
          closeButton:
            'order-last flex size-5 shrink-0 items-center justify-center rounded-control text-ink-2 transition-colors duration-150 hover:bg-surface-hover hover:text-ink',
          ...toastOptions?.classNames,
        },
      }}
      {...props}
    />
  );
}

export interface NotifyErrorOptions extends Omit<ExternalToast, 'duration' | 'closeButton' | 'description'> {
  description?: ReactNode;
  /** problem+json 的 request_id：写进描述，便于反馈问题时引用。 */
  requestId?: string;
}

/**
 * 按通知策略发通知：只在结果不在视野里时用（异步任务完成、操作失败）。结果就在当前视野里的操作不发通知。
 */
export const notify = {
  /** 异步操作完成：4 秒后消失，可带跳转动作。 */
  success(message: ReactNode, options?: ExternalToast) {
    return toast.success(message, options);
  },
  /** 操作失败：不自动消失、带关闭按钮；有 request_id 时写在描述里。 */
  error(message: ReactNode, { requestId, description, ...options }: NotifyErrorOptions = {}) {
    return toast.error(message, {
      ...options,
      duration: Number.POSITIVE_INFINITY,
      closeButton: true,
      description:
        requestId === undefined ? (
          description
        ) : (
          <span className="flex flex-col gap-1">
            {description}
            <span>
              请求 ID <code className="text-ink">{requestId}</code>
            </span>
          </span>
        ),
    });
  },
  /** 一般信息。 */
  info(message: ReactNode, options?: ExternalToast) {
    return toast.info(message, options);
  },
  dismiss(id?: string | number) {
    return toast.dismiss(id);
  },
};
