import { QueryErrorResetBoundary } from '@tanstack/react-query';
import { Component, Suspense, use, useEffect, useState, type ErrorInfo, type ReactNode } from 'react';

import { ErrorState } from '../error-state';

/** 骨架出现前的等待（更快返回的请求不显示骨架）与出现后的最短显示时长，frontend-design.md「加载」。 */
export const SKELETON_DELAY_MS = 150;
export const SKELETON_MIN_MS = 300;

export interface DataRegionProps {
  /** 加载中的骨架：几何与最终布局一致（行高、列宽、图标位置），加载完成时不跳动。 */
  skeleton: ReactNode;
  /** 加载失败时写明发生了什么（"无法加载会话列表"）；原因、下一步与请求 ID 来自 problem+json。 */
  errorTitle: string;
  /** 错误态的版式：region 在区域中央，inline 用于面板里的一块。 */
  errorSize?: 'region' | 'inline';
  /** 用 `useSuspenseQuery` 读数据的内容。 */
  children: ReactNode;
}

/**
 * 骨架最短显示时长的闸门：骨架出现时开始计时，内容在计时结束前就绪也要等到结束。计时用的 Promise 兑现后标上 React `use`
 * 认得的状态，再次读取时不再挂起。
 */
class SkeletonGate {
  #pending: Promise<void> | null = null;

  /** 骨架开始显示。 */
  open(ms: number): void {
    const promise = new Promise<void>((resolve) => setTimeout(resolve, ms)) as Promise<void> & {
      status?: string;
    };
    void promise.then(() => {
      promise.status = 'fulfilled';
    });
    this.#pending = promise;
  }

  /** 新一次加载开始（骨架还没出现）。 */
  reset(): void {
    this.#pending = null;
  }

  /** 骨架出现过时，内容要等到的 Promise。 */
  get pending(): Promise<void> | null {
    return this.#pending;
  }
}

function Fallback({ gate, skeleton }: { gate: SkeletonGate; skeleton: ReactNode }) {
  const [visible, setVisible] = useState(false);
  useEffect(() => {
    gate.reset();
    const timer = window.setTimeout(() => {
      gate.open(SKELETON_MIN_MS);
      setVisible(true);
    }, SKELETON_DELAY_MS);
    return () => window.clearTimeout(timer);
  }, [gate]);
  return (
    <div aria-busy="true" className="contents">
      {visible && skeleton}
    </div>
  );
}

function HoldUntil({ gate }: { gate: SkeletonGate }) {
  const { pending } = gate;
  if (pending) use(pending);
  return null;
}

interface BoundaryProps {
  onReset: () => void;
  render: (error: unknown, retry: () => void) => ReactNode;
  children: ReactNode;
}

class ErrorBoundary extends Component<BoundaryProps, { error: unknown }> {
  override state: { error: unknown } = { error: null };

  static getDerivedStateFromError(error: unknown) {
    return { error };
  }

  override componentDidCatch(error: unknown, info: ErrorInfo) {
    // 区域内的错误已在界面上说明；保留在控制台，便于排查组件本身的异常。
    console.error(error, info.componentStack);
  }

  retry = () => {
    this.props.onReset();
    this.setState({ error: null });
  };

  override render() {
    if (this.state.error !== null) return this.props.render(this.state.error, this.retry);
    return this.props.children;
  }
}

/**
 * 区域级的数据加载（frontend-design.md「状态与反馈」）：每个独立的数据区域一个 Suspense 边界，不整页转圈。
 * 加载时 150ms 后才出现骨架、出现后至少保持 300ms；加载失败时只在这个区域显示 problem+json 的错误与重试，
 * 页面的其他部分照常可用。内容用 TanStack Query 的 `useSuspenseQuery` 读数据。
 */
export function DataRegion({ skeleton, errorTitle, errorSize = 'region', children }: DataRegionProps) {
  const [gate] = useState(() => new SkeletonGate());
  return (
    <QueryErrorResetBoundary>
      {({ reset }) => (
        <ErrorBoundary
          onReset={reset}
          render={(error, retry) => (
            <ErrorState error={error} title={errorTitle} size={errorSize} onRetry={retry} />
          )}
        >
          <Suspense fallback={<Fallback gate={gate} skeleton={skeleton} />}>
            <HoldUntil gate={gate} />
            {children}
          </Suspense>
        </ErrorBoundary>
      )}
    </QueryErrorResetBoundary>
  );
}
