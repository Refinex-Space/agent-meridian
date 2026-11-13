import type { Meta, StoryObj } from '@storybook/react-vite';
import { useEffect } from 'react';

import { storyFrame } from '@/test/stories';

import { Button } from './button';
import { notify, Toaster } from './sonner';

function Demo({ show }: { show: () => void }) {
  useEffect(() => {
    show();
    return () => {
      notify.dismiss();
    };
  }, [show]);
  return (
    <>
      <Button onClick={show}>再发一条</Button>
      <Toaster />
    </>
  );
}

/** 通知：只在结果不在视野里时用；同时最多 3 条，成功 4 秒消失，错误手动关闭。 */
const meta = {
  title: '基元/通知',
  component: Toaster,
  decorators: [storyFrame()],
} satisfies Meta<typeof Toaster>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
  name: '默认',
  render: () => (
    <Demo
      show={() =>
        notify.success('文档写入完成', {
          description: '季度经营报告.pdf 已切分为 42 段',
          duration: Number.POSITIVE_INFINITY,
          action: { label: '查看', onClick: () => undefined },
        })
      }
    />
  ),
};

export const Error: Story = {
  name: '错误',
  render: () => (
    <Demo
      show={() =>
        notify.error('无法吊销 API Key', {
          description: '这个 Key 已被其他管理员吊销。',
          requestId: 'req_01JC9A7M2K',
        })
      }
    />
  ),
};

export const LongContent: Story = {
  name: '长内容',
  render: () => (
    <Demo
      show={() =>
        notify.info('知识库重建索引已开始', {
          description:
            '华东政策文件库共 1,284 份文档，预计 20 分钟完成；期间检索仍使用旧索引，完成后自动切换并发送通知。',
          duration: Number.POSITIVE_INFINITY,
        })
      }
    />
  ),
};
