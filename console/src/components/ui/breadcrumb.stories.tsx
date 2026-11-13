import type { Meta, StoryObj } from '@storybook/react-vite';

import { focus, hover, storyFrame } from '@/test/stories';

import {
  Breadcrumb,
  BreadcrumbItem,
  BreadcrumbLink,
  BreadcrumbList,
  BreadcrumbPage,
  BreadcrumbSeparator,
} from './breadcrumb';

function Example({ last = 'ses_01JC8Z4V7Q' }: { last?: string }) {
  return (
    <Breadcrumb>
      <BreadcrumbList>
        <BreadcrumbItem>
          <BreadcrumbLink href="#sessions">会话</BreadcrumbLink>
        </BreadcrumbItem>
        <BreadcrumbSeparator />
        <BreadcrumbItem>
          <BreadcrumbPage className="font-mono text-12">{last}</BreadcrumbPage>
        </BreadcrumbItem>
      </BreadcrumbList>
    </Breadcrumb>
  );
}

/** 面包屑：所在位置的层级，斜线分隔。 */
const meta = {
  title: '基元/面包屑',
  component: Breadcrumb,
  decorators: [storyFrame('w-120')],
} satisfies Meta<typeof Breadcrumb>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = { name: '默认', render: () => <Example /> };

export const Hover: Story = {
  name: '悬停',
  parameters: hover('[data-slot=breadcrumb-link]'),
  render: () => <Example />,
};

export const Focus: Story = {
  name: '聚焦',
  parameters: focus('[data-slot=breadcrumb-link]'),
  render: () => <Example />,
};

export const LongContent: Story = {
  name: '长内容',
  decorators: [storyFrame('w-64')],
  render: () => <Example last="ses_01JC8Z4V7QX3M9N2P8R5T6W0YA" />,
};
