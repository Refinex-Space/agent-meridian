import type { Meta, StoryObj } from '@storybook/react-vite';

import { storyFrame } from '@/test/stories';

import { Button } from './button';
import {
  Dialog,
  DialogBody,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from './dialog';
import { Input } from './input';
import { Label } from './label';

function KeyDialog({ pending = false, error }: { pending?: boolean; error?: string }) {
  return (
    <Dialog defaultOpen>
      <DialogTrigger asChild>
        <Button variant="primary">创建 API Key</Button>
      </DialogTrigger>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>创建 API Key</DialogTitle>
          <DialogDescription>Key 只在创建后显示一次。</DialogDescription>
        </DialogHeader>
        <div className="flex flex-col gap-1">
          <Label htmlFor="key-name">名称</Label>
          <Input
            id="key-name"
            defaultValue="经营分析看板"
            disabled={pending}
            aria-invalid={error ? true : undefined}
            aria-describedby={error ? 'key-name-error' : undefined}
          />
          {error && (
            <p id="key-name-error" className="text-12 text-danger">
              {error}
            </p>
          )}
        </div>
        <DialogFooter>
          <DialogClose asChild>
            <Button disabled={pending}>取消</Button>
          </DialogClose>
          <Button variant="primary" pending={pending}>
            创建
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

/** 对话框：居中、带遮罩；出现 200ms，退出 150ms。 */
const meta = {
  title: '基元/对话框',
  component: Dialog,
  decorators: [storyFrame()],
} satisfies Meta<typeof Dialog>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = { name: '默认', render: () => <KeyDialog /> };

export const Loading: Story = { name: '加载', render: () => <KeyDialog pending /> };

export const Error: Story = {
  name: '错误',
  render: () => <KeyDialog error="同一项目中已有同名的 API Key" />,
};

export const LongContent: Story = {
  name: '长内容',
  render: () => (
    <Dialog defaultOpen>
      <DialogContent size="lg">
        <DialogHeader>
          <DialogTitle>发布记录</DialogTitle>
        </DialogHeader>
        <DialogBody label="发布记录">
          <ol className="flex flex-col text-13">
            {Array.from({ length: 30 }, (_, i) => (
              <li key={i} className="flex h-10 items-center justify-between border-b border-rule">
                <span>v{30 - i}</span>
                <span className="text-ink-2">更新了系统提示词与工具权限</span>
              </li>
            ))}
          </ol>
        </DialogBody>
        <DialogFooter>
          <DialogClose asChild>
            <Button>关闭</Button>
          </DialogClose>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  ),
};
