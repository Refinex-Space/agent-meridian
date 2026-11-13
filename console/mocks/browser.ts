import { setupWorker } from 'msw/browser';

import { q3Handlers } from './handlers/q3';

/** 开发服务器与原型应用用的 Service Worker：会话详情原型的实时情形。 */
export const worker = setupWorker(...q3Handlers('live'));
