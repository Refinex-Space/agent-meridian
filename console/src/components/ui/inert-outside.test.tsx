import { act, render } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { InertOutside } from './inert-outside';

/** 等 MutationObserver 的回调（微任务）。 */
const flush = () => act(() => Promise.resolve());

describe('InertOutside', () => {
  it('给 Radix 标记为隐藏的元素加 inert，标记撤销时立即移除（不等本组件卸载）', async () => {
    const shell = document.createElement('div');
    document.body.append(shell);
    // 关闭的 Select 也会挂载内容（Radix 把它渲染进文档片段），所以本组件可能一直存在。
    const { unmount } = render(<InertOutside />);

    // 对话框打开：Radix 标记外部元素。
    shell.setAttribute('data-aria-hidden', 'true');
    await flush();
    expect(shell.inert).toBe(true);

    // 对话框关闭：标记撤销，inert 随之移除，页面重新可以聚焦。
    shell.removeAttribute('data-aria-hidden');
    await flush();
    expect(shell.inert).toBe(false);

    unmount();
    shell.remove();
  });

  it('不改动不是本组件加上的 inert', async () => {
    const other = document.createElement('div');
    other.inert = true;
    other.setAttribute('data-aria-hidden', 'true');
    document.body.append(other);
    const { unmount } = render(<InertOutside />);
    other.removeAttribute('data-aria-hidden');
    await flush();
    unmount();
    expect(other.inert).toBe(true);
    other.remove();
  });
});
