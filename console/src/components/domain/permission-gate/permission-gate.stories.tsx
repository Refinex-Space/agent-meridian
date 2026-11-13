import type { Meta, StoryObj } from '@storybook/react-vite';

import { Button } from '@/components/ui/button';
import { focus, storyFrame } from '@/test/stories';

import { PermissionGate } from './permission-gate';

const REASON = '需要编辑智能体的权限，请联系项目管理员';

/** 按权限隐藏或禁用操作，并说明原因；整块无权限时用说明代替空白。 */
const meta = {
  title: '领域组件/PermissionGate',
  component: PermissionGate,
  decorators: [storyFrame(undefined, 'pt-16')],
  args: {
    allowed: true,
    mode: 'disable',
    reason: REASON,
    children: <Button variant="primary">发布版本</Button>,
  },
} satisfies Meta<typeof PermissionGate>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Allowed: Story = { name: '有权限' };

export const Disabled: Story = { name: '禁用并说明', args: { allowed: false } };

export const Focus: Story = {
  name: '聚焦',
  args: { allowed: false },
  parameters: focus('[data-slot=button]'),
};

export const Hidden: Story = {
  name: '隐藏',
  render: () => (
    <div className="flex items-center gap-2">
      <Button>编辑</Button>
      <PermissionGate allowed={false} mode="hide" reason={REASON}>
        <Button variant="danger">删除智能体</Button>
      </PermissionGate>
    </div>
  ),
};

export const Explain: Story = {
  name: '整块无权限',
  decorators: [storyFrame('w-200')],
  render: () => (
    <PermissionGate
      allowed={false}
      mode="explain"
      title="没有权限查看凭据"
      reason="查看凭据需要租户管理员或所有者角色，请联系租户管理员。"
    />
  ),
};
