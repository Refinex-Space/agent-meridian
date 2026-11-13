import type { Meta, StoryObj } from '@storybook/react-vite';
import { userEvent, within } from 'storybook/test';

import { ApiError } from '@/lib/api/sessions';
import { storyFrame } from '@/test/stories';

import { DangerConfirmDialog } from './danger-confirm-dialog';

/** 不可逆操作的确认：写明后果；最高风险的操作要求输入名称；失败就地显示原因。 */
const meta = {
  title: '领域组件/DangerConfirmDialog',
  component: DangerConfirmDialog,
  decorators: [storyFrame()],
  args: {
    open: true,
    onOpenChange: () => undefined,
    title: '归档会话',
    consequences:
      '归档后，这个会话不能再发送消息，正在运行的一轮会被中断；事件与工作区文件按保留策略保存 30 天。',
    confirmLabel: '归档会话',
    onConfirm: () => new Promise<void>(() => undefined),
  },
} satisfies Meta<typeof DangerConfirmDialog>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = { name: '默认' };

export const ConfirmName: Story = {
  name: '输入名称',
  args: {
    title: '吊销 API Key 经营分析看板',
    consequences: (
      <>
        <p>吊销后，使用这个 Key 的业务系统会立刻收到 401，正在运行的会话不受影响。</p>
        <p>这个操作不能撤销，需要时重新创建一个 Key。</p>
      </>
    ),
    confirmLabel: '吊销 API Key',
    confirmName: '经营分析看板',
  },
};

export const Loading: Story = {
  name: '确认中',
  play: async ({ canvasElement }) => {
    const body = within(canvasElement.ownerDocument.body);
    await userEvent.click(body.getByRole('button', { name: '归档会话' }));
  },
};

export const Error: Story = {
  name: '失败',
  args: {
    onConfirm: () =>
      Promise.reject(
        new ApiError(409, {
          type: 'urn:meridian:error:session.terminated',
          title: 'Conflict',
          status: 409,
          detail: '这个会话已被其他用户归档。',
          instance: '/v1/sessions/ses_01JC/archive',
          code: 'session.terminated',
          request_id: 'req_01JC9A7M2KX4Q8',
        }),
      ),
  },
  play: async ({ canvasElement }) => {
    const body = within(canvasElement.ownerDocument.body);
    await userEvent.click(body.getByRole('button', { name: '归档会话' }));
  },
};

export const LongContent: Story = {
  name: '长内容',
  args: {
    title: '删除知识库 华东大区经营分析政策文件与历年季度报告汇编',
    consequences: (
      <>
        <p>删除后，知识库中的 1,284 份文档、全部切片与向量索引会被永久删除，存储计量随之停止。</p>
        <p>
          引用这个知识库的 3 个智能体版本在下一轮检索时会返回"知识库不存在"，需要先修改智能体再发布新版本。
        </p>
      </>
    ),
    confirmLabel: '删除知识库',
    confirmName: '华东大区经营分析政策文件与历年季度报告汇编',
  },
};
