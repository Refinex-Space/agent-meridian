import { cloneElement, useId, type MouseEvent, type ReactElement } from 'react';

import { LockSimpleIcon } from '@/components/ui/glyphs';
import { Icon } from '@/components/ui/icon';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';

import { StateLayout } from '../empty-state/state-layout';

/** 被守卫的操作元素需要接受的属性（Button 与原生 button 都满足）。 */
interface GuardedProps {
  'aria-disabled'?: boolean | 'true' | 'false';
  'aria-describedby'?: string;
  onClick?: (event: MouseEvent<HTMLElement>) => void;
}

interface CommonProps {
  /** 当前用户能否执行（由 `GET /admin/v1/me/access` 的操作类别与作用域算出，不在前端复刻角色矩阵）。 */
  allowed: boolean;
  /** 缺少什么权限、找谁授予，例如"需要编辑智能体的权限，请联系项目管理员"。 */
  reason: string;
}

export type PermissionGateProps = CommonProps &
  (
    | {
        /**
         * hide：没有权限时不渲染（例如表格的行操作）；disable：渲染为不可用并在提示中说明原因（页面的主操作，
         * 让用户知道这里有这个操作）。
         */
        mode: 'hide' | 'disable';
        children: ReactElement<GuardedProps>;
      }
    | {
        /** explain：整块区域没有权限时，用说明代替空白，写明缺少的权限。 */
        mode: 'explain';
        /** 这一块是什么，例如"没有权限查看凭据"。 */
        title: string;
        children?: never;
      }
  );

/**
 * 按权限隐藏或禁用操作，并说明原因。禁用不用原生 disabled：按钮保持可聚焦（aria-disabled），
 * 悬停或聚焦时提示原因，读屏经 aria-describedby 读到原因；点击不会触发原来的操作。
 */
export function PermissionGate(props: PermissionGateProps) {
  const reasonId = useId();
  if (props.allowed) return props.mode === 'explain' ? null : props.children;
  if (props.mode === 'hide') return null;
  if (props.mode === 'explain') {
    return (
      <StateLayout
        role="status"
        figure={<Icon glyph={LockSimpleIcon} size={24} className="text-ink-2" />}
        title={props.title}
      >
        <p>{props.reason}</p>
      </StateLayout>
    );
  }
  const child = props.children;
  const describedBy = [child.props['aria-describedby'], reasonId].filter(Boolean).join(' ');
  return (
    <>
      <Tooltip>
        <TooltipTrigger asChild>
          {cloneElement(child, {
            'aria-disabled': true,
            'aria-describedby': describedBy,
            onClick: (event: MouseEvent<HTMLElement>) => event.preventDefault(),
          })}
        </TooltipTrigger>
        <TooltipContent>{props.reason}</TooltipContent>
      </Tooltip>
      <span id={reasonId} hidden>
        {props.reason}
      </span>
    </>
  );
}
