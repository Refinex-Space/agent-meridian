/**
 * 故事的公共外框与状态参数：基元和领域组件的故事都放在纸面（surface）上，四周 32px 留白，宽度按内容。
 * 悬停、聚焦用 storybook-addon-pseudo-states 强制伪类（`parameters.pseudo`），截图与 a11y 检查不依赖真实鼠标。
 */
import type { Decorator } from '@storybook/react-vite';

import { cn } from '@/lib/cn';

/** 纸面外框：fullscreen 布局下给故事一块 surface 底。`width` 限制内容宽度（例如表单控件 320px）。 */
export function storyFrame(width?: string, className?: string): Decorator {
  function Frame(Story: Parameters<Decorator>[0]) {
    return (
      <div className={cn('min-h-screen bg-surface p-8 text-ink', className)}>
        <div className={cn('flex flex-col items-start gap-4 *:max-w-full', width, width && 'items-stretch')}>
          <Story />
        </div>
      </div>
    );
  }
  return Frame;
}

/** 强制悬停：只对选择器命中的元素生效（全局强制会让外框也进入悬停）。 */
export function hover(...selectors: string[]) {
  return { pseudo: { hover: selectors } };
}

/** 强制键盘聚焦：:focus-visible 与 :focus 只对选择器命中的元素生效（全局的焦点环规则对任何元素都会画出轮廓）。 */
export function focus(...selectors: string[]) {
  return { pseudo: { focusVisible: selectors, focus: selectors } };
}
