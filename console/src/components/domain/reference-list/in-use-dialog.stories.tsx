import type { Meta, StoryObj } from '@storybook/react-vite';

import { storyFrame } from '@/test/stories';

import { InUseDialog } from './in-use-dialog';

/** 删除被引用的资源时的说明：原因与引用方清单，只有"知道了"。 */
const meta = {
  title: '领域组件/InUseDialog',
  component: InUseDialog,
  decorators: [storyFrame()],
  args: {
    name: 'DashScope 生产',
    nextStep: '先让下面的资源改用其他凭据，或删除它们，再删除这个凭据。',
    onClose: () => undefined,
    references: [
      { type: 'provider_account', id: 'prv_01JC8Z4E7G9J1L3N5Q7S9U1W3Y', name: 'DashScope' },
      { type: 'tenant_settings', id: 'ten_01JC8Z1A2B3C4D5E6F7G8H9J0K', name: null },
      { type: 'agent_version', id: 'agv_01JC8Z6B7Q9X3M2N6P0R5T8W1Y', name: '经营分析助手 v12' },
    ],
  },
} satisfies Meta<typeof InUseDialog>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = { name: '默认' };
