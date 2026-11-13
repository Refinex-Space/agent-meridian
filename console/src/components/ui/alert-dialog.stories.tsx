import type { Meta, StoryObj } from '@storybook/react-vite';

import { storyFrame } from '@/test/stories';

import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from './alert-dialog';

/** 警示对话框：必须明确选择，点遮罩不关闭。不可逆操作用领域组件 DangerConfirmDialog。 */
const meta = {
  title: '基元/警示对话框',
  component: AlertDialog,
  decorators: [storyFrame()],
} satisfies Meta<typeof AlertDialog>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
  name: '默认',
  render: () => (
    <AlertDialog defaultOpen>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>放弃未保存的更改？</AlertDialogTitle>
          <AlertDialogDescription>系统提示词与工具权限的修改都会丢失。</AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel>继续编辑</AlertDialogCancel>
          <AlertDialogAction variant="danger">放弃更改</AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  ),
};
