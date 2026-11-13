import type { Meta, StoryObj } from '@storybook/react-vite';
import { useState } from 'react';
import { userEvent, within } from 'storybook/test';

import { Label } from '@/components/ui/label';
import { ApiError } from '@/lib/api/errors';
import { storyFrame } from '@/test/stories';

import { ResourceSelect, type ResourceSource } from './resource-picker';

const SOURCE: ResourceSource = {
  options: [
    { id: 'mdl_qwen_plus', name: 'qwen-plus', mono: true, detail: '上下文 128K' },
    { id: 'mdl_qwen_max', name: 'qwen-max', mono: true, detail: '上下文 32K' },
    {
      id: 'mdl_deepseek',
      name: 'deepseek-v3',
      mono: true,
      detail: '上下文 64K',
      status: { kind: 'failure', label: '不可用' },
    },
  ],
  loading: false,
  error: null,
  onRetry: () => undefined,
};

function Select({ source, initial = 'mdl_qwen_plus' }: { source: ResourceSource; initial?: string | null }) {
  const [value, setValue] = useState<string | null>(initial);
  return (
    <div className="flex flex-col gap-2">
      <Label htmlFor="story-model">主模型</Label>
      <ResourceSelect
        id="story-model"
        value={value}
        onChange={setValue}
        source={source}
        searchLabel="搜索模型"
        emptyLabel="本租户的模型目录里还没有对话模型"
      />
    </div>
  );
}

/** 可搜索的资源选择器（模型、知识库、技能、MCP 服务、凭据、价格表）：选项带资源当前的状态。 */
const meta = {
  title: '领域组件/ResourceSelect',
  component: Select,
  decorators: [storyFrame('w-100')],
  args: { source: SOURCE },
} satisfies Meta<typeof Select>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = { name: '默认' };

export const Open: Story = {
  name: '展开',
  play: async ({ canvasElement }) => {
    await userEvent.click(within(canvasElement).getByRole('button', { name: '主模型' }));
  },
};

export const Missing: Story = { name: '已删除的资源', args: { initial: 'mdl_removed' } };

export const Loading: Story = {
  name: '加载中',
  args: { source: { ...SOURCE, options: [], loading: true } },
  play: Open.play,
};

export const Failed: Story = {
  name: '加载失败',
  args: {
    source: {
      ...SOURCE,
      options: [],
      error: new ApiError(503, {
        type: 'urn:meridian:error:service.unavailable',
        title: '服务不可用',
        status: 503,
        detail: 'control-plane 暂时不可用。',
        instance: '/admin/v1/tenants/ten_1/models',
        code: 'service.unavailable',
        request_id: 'req_01JCQC2E5G8J1L4N7Q0S3V6X9Z',
      }),
    },
  },
  play: Open.play,
};

export const Empty: Story = {
  name: '没有资源',
  args: { source: { ...SOURCE, options: [] }, initial: null },
  play: Open.play,
};
