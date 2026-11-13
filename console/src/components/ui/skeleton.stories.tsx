import type { Meta, StoryObj } from '@storybook/react-vite';

import { storyFrame } from '@/test/stories';

import { Skeleton } from './skeleton';

/** 骨架：按最终内容的几何绘制，只做不透明度呼吸；减少动效时静止。它本身就是"加载"状态。 */
const meta = {
  title: '基元/骨架',
  component: Skeleton,
  decorators: [storyFrame('w-120')],
} satisfies Meta<typeof Skeleton>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Loading: Story = {
  name: '加载',
  render: () => (
    <div aria-busy className="flex flex-col">
      {[0, 1, 2, 3].map((row) => (
        <div key={row} className="flex h-10 items-center gap-6 border-b border-rule">
          <Skeleton className="h-3 w-28" />
          <Skeleton className="h-3 w-24" />
          <Skeleton className="h-3 w-16" />
          <Skeleton className="ml-auto h-3 w-12" />
        </div>
      ))}
    </div>
  ),
};
