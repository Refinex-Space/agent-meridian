import type { Meta, StoryObj } from '@storybook/react-vite';
import { QueryClient, QueryClientProvider, useSuspenseQuery } from '@tanstack/react-query';
import { useState } from 'react';

import { Skeleton } from '@/components/ui/skeleton';
import { ApiError } from '@/lib/api/errors';
import { storyFrame } from '@/test/stories';

import { DataRegion } from './data-region';

const ROWS = ['经营分析助手', '客服质检', '门店巡检报告'];

/** 三行的列表骨架：与下面的列表同样的行高与左缘。 */
const skeleton = (
  <ul className="flex flex-col">
    {ROWS.map((row) => (
      <li key={row} className="flex h-10 items-center border-b border-rule">
        <Skeleton className="h-4 w-40" />
      </li>
    ))}
  </ul>
);

function Agents({ load }: { load: () => Promise<string[]> }) {
  const { data } = useSuspenseQuery({ queryKey: ['story-agents'], queryFn: load, retry: false });
  return (
    <ul className="flex flex-col text-13">
      {data.map((name) => (
        <li key={name} className="flex h-10 items-center border-b border-rule">
          {name}
        </li>
      ))}
    </ul>
  );
}

function Demo({ load }: { load: () => Promise<string[]> }) {
  const [client] = useState(() => new QueryClient());
  return (
    <QueryClientProvider client={client}>
      <DataRegion skeleton={skeleton} errorTitle="无法加载智能体">
        <Agents load={load} />
      </DataRegion>
    </QueryClientProvider>
  );
}

/** 区域级的数据加载：骨架 150ms 后出现、至少 300ms；失败时只在这个区域内说明原因与重试。 */
const meta = {
  title: '领域组件/DataRegion',
  component: DataRegion,
  decorators: [storyFrame('w-160')],
  args: { skeleton, errorTitle: '无法加载智能体', children: null },
} satisfies Meta<typeof DataRegion>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Loaded: Story = { name: '已加载', render: () => <Demo load={() => Promise.resolve(ROWS)} /> };

export const Loading: Story = {
  name: '加载中',
  render: () => <Demo load={() => new Promise<string[]>(() => undefined)} />,
};

export const Failed: Story = {
  name: '加载失败',
  render: () => (
    <Demo
      load={() =>
        Promise.reject(
          new ApiError(503, {
            type: 'urn:meridian:error:internal',
            title: 'Service Unavailable',
            status: 503,
            detail: 'control-plane 暂时不可用。',
            instance: '/admin/v1/tenants/ten_1/projects/prj_1/agents',
            code: 'internal.unavailable',
            request_id: 'req_01JC9B2N4QX7',
          }),
        )
      }
    />
  ),
};
