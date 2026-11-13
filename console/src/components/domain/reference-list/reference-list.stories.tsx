import type { Meta, StoryObj } from '@storybook/react-vite';

import { storyFrame } from '@/test/stories';

import { ReferenceList } from './reference-list';

const meta = {
  title: '领域组件/ReferenceList',
  component: ReferenceList,
  decorators: [storyFrame('w-120')],
  args: {
    label: '引用这个凭据的资源',
    references: [
      { type: 'provider_account', id: 'prv_01JC8Z4E7G9J1L3N5Q7S9U1W3Y', name: 'DashScope 生产' },
      { type: 'mcp_server', id: 'mcp_01JC8Z6B7Q9X3M2N6P0R5T8W1Y', name: 'crm' },
      { type: 'tenant_settings', id: 'ten_01JC8Z1A2B3C4D5E6F7G8H9J0K', name: null },
    ],
  },
} satisfies Meta<typeof ReferenceList>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = { name: '默认' };

export const LongName: Story = {
  name: '长名称',
  args: {
    references: [
      {
        type: 'agent_version',
        id: 'agv_01JC8Z4E7G9J1L3N5Q7S9U1W3Y',
        name: 'east-china-retail-weekly-report-assistant-with-a-very-long-name v128',
      },
    ],
  },
};
