import '@testing-library/jest-dom/vitest';

import { cleanup } from '@testing-library/react';
import { afterEach } from 'vitest';

afterEach(() => {
  cleanup();
});

// jsdom 没有 matchMedia（motion 的 useReducedMotion 读取它）与 ResizeObserver（虚拟滚动测量行高）。
if (!window.matchMedia) {
  window.matchMedia = (query: string): MediaQueryList => ({
    matches: false,
    media: query,
    onchange: null,
    addEventListener: () => undefined,
    removeEventListener: () => undefined,
    addListener: () => undefined,
    removeListener: () => undefined,
    dispatchEvent: () => false,
  });
}

if (typeof window.ResizeObserver === 'undefined') {
  window.ResizeObserver = class {
    observe(): void {}
    unobserve(): void {}
    disconnect(): void {}
  };
}

// jsdom 没有指针捕获（Sonner 的通知在按下时捕获指针，用于滑动关闭）。
if (!('setPointerCapture' in Element.prototype)) {
  Object.assign(Element.prototype, {
    setPointerCapture: () => undefined,
    releasePointerCapture: () => undefined,
    hasPointerCapture: () => false,
  });
}

// jsdom 没有 scrollIntoView（cmdk 把高亮的选项滚进视野）。
if (!Element.prototype.scrollIntoView) {
  Element.prototype.scrollIntoView = () => undefined;
}
