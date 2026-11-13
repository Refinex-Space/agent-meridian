import { useEffect } from 'react';

/**
 * Radix 的选择器与模态下拉菜单打开时，只给浮层以外的内容加 aria-hidden（焦点被困在浮层里）。外面的控件仍可经程序聚焦，
 * axe 判为 aria-hidden-focus。这个组件放在浮层内容里，给 Radix 标记为隐藏的元素（`data-aria-hidden`）再加上 inert，
 * 与对话框（aria-modal）的语义一致；标记撤销或本组件卸载时，移除它加上的 inert。
 *
 * 关闭的选择器也会挂载内容（Radix 把它渲染进文档片段以收集选项），本组件因此可能长期存在：其他浮层（对话框）的标记撤销时
 * 必须立即移除 inert，否则对话框关闭后整个页面仍是 inert，无法聚焦（T22 发现）。
 */
export function InertOutside() {
  useEffect(() => {
    const marked = new Set<HTMLElement>();
    const sync = () => {
      for (const el of document.querySelectorAll<HTMLElement>('[data-aria-hidden="true"]')) {
        if (!el.inert) {
          el.inert = true;
          marked.add(el);
        }
      }
      for (const el of marked) {
        if (el.getAttribute('data-aria-hidden') !== 'true') {
          el.inert = false;
          marked.delete(el);
        }
      }
    };
    // Radix 在浮层挂载后的副作用里加 aria-hidden，晚于本组件的副作用：观察属性变化再补上或撤销 inert。
    sync();
    const observer = new MutationObserver(sync);
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
