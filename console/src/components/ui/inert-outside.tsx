import { useEffect } from 'react';

/**
 * Radix 的选择器与模态下拉菜单打开时，只给浮层以外的内容加 aria-hidden（焦点被困在浮层里）。外面的控件仍可经程序聚焦，
 * axe 判为 aria-hidden-focus。这个组件放在浮层内容里，在它存在期间给 Radix 标记为隐藏的元素（`data-aria-hidden`）
 * 再加上 inert，与对话框（aria-modal）的语义一致；浮层关闭时移除它加上的 inert。
 */
export function InertOutside() {
  useEffect(() => {
    const marked = new Set<HTMLElement>();
    const apply = () => {
      for (const el of document.querySelectorAll<HTMLElement>('[data-aria-hidden="true"]')) {
        if (!el.inert) {
          el.inert = true;
          marked.add(el);
        }
      }
    };
    // Radix 在浮层挂载后的副作用里加 aria-hidden，晚于本组件的副作用：观察属性变化再补上 inert。
    apply();
    const observer = new MutationObserver(apply);
    observer.observe(document.body, {
      subtree: true,
      attributes: true,
      attributeFilter: ['data-aria-hidden'],
    });
    return () => {
      observer.disconnect();
      for (const el of marked) el.inert = false;
    };
  }, []);
  return null;
}
