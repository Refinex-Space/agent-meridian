import type { Meta, StoryObj } from '@storybook/react-vite';

import { ApiError } from '@/lib/api/sessions';
import type { Problem } from '@/lib/api/types';
import { focus, hover, storyFrame } from '@/test/stories';

import { ErrorState } from './error-state';

const problem = (over: Partial<Problem> = {}): Problem => ({
  type: 'urn:meridian:error:internal',
  title: 'Internal Server Error',
  status: 503,
  detail: '运行时节点正在重启，暂时无法读取事件。',
  instance: '/v1/sessions',
  code: 'internal.unavailable',
  request_id: 'req_01JC9A7M2KX4Q8',
  ...over,
});

/** problem+json 错误：发生了什么、原因、下一步、可复制的请求 ID 与重试；区域级居中，行内用浅红底。 */
const meta = {
  title: '领域组件/ErrorState',
  component: ErrorState,
  decorators: [storyFrame('w-200')],
  args: { error: new ApiError(503, problem()), title: '无法加载会话列表', onRetry: () => undefined },
} satisfies Meta<typeof ErrorState>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = { name: '默认' };

export const Hover: Story = { name: '悬停', parameters: hover('[data-slot=button]') };

export const Focus: Story = { name: '聚焦', parameters: focus('[data-slot=button]') };

export const Loading: Story = { name: '重试中', args: { retrying: true } };

export const NotFound: Story = {
  name: '对象不存在',
  args: {
    title: undefined,
    onRetry: undefined,
    error: new ApiError(
      404,
      problem({ status: 404, code: 'session.not_found', detail: '会话 ses_01JC8Z4V7Q 不存在。' }),
    ),
  },
};

export const Network: Story = {
  name: '网络不可达',
  args: { error: new TypeError('Failed to fetch') },
};

export const Validation: Story = {
  name: '校验失败（行内）',
  decorators: [storyFrame('w-120')],
  args: {
    size: 'inline',
    title: '没有保存',
    onRetry: undefined,
    error: new ApiError(
      422,
      problem({
        status: 422,
        code: 'request.validation_failed',
        detail: '有 2 个字段不符合要求。',
        errors: [
          { field: 'name', message: '不能为空' },
          { field: 'model_id', message: '模型不存在或已停用' },
        ],
      }),
    ),
  },
};

export const LongContent: Story = {
  name: '长内容',
  args: {
    error: new ApiError(
      503,
      problem({
        detail:
          '上游模型供应商在 30 秒内没有返回结果，平台已按重试策略重试 3 次仍然失败；本轮已停止，会话状态回到空闲，已产生的事件都已保存。',
      }),
    ),
  },
};
