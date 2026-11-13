import type { Meta, StoryObj } from '@storybook/react-vite';

import { storyFrame } from '@/test/stories';

import { Button } from './button';
import { Input } from './input';
import { Label } from './label';
import {
  Sheet,
  SheetBody,
  SheetClose,
  SheetContent,
  SheetDescription,
  SheetFooter,
  SheetHeader,
  SheetTitle,
} from './sheet';

/** 抽屉：在当前页查看或编辑一个对象；从右侧滑入 250ms。 */
const meta = {
  title: '基元/抽屉',
  component: Sheet,
  decorators: [storyFrame()],
} satisfies Meta<typeof Sheet>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
  name: '默认',
  render: () => (
    <Sheet defaultOpen>
      <SheetContent>
        <SheetHeader>
          <SheetTitle>编辑通知地址</SheetTitle>
          <SheetDescription>会话状态变化时向这个地址发送签名的事件。</SheetDescription>
        </SheetHeader>
        <SheetBody className="flex flex-col gap-4">
          <div className="flex flex-col gap-1">
            <Label htmlFor="hook-url">地址</Label>
            <Input id="hook-url" defaultValue="https://ops.example.cn/hooks/meridian" />
          </div>
        </SheetBody>
        <SheetFooter>
          <SheetClose asChild>
            <Button>取消</Button>
          </SheetClose>
          <Button variant="primary">保存</Button>
        </SheetFooter>
      </SheetContent>
    </Sheet>
  ),
};

export const LongContent: Story = {
  name: '长内容',
  render: () => (
    <Sheet defaultOpen>
      <SheetContent size="lg">
        <SheetHeader>
          <SheetTitle>投递记录：https://ops.example.cn/hooks/meridian/very/long/path/for/delivery</SheetTitle>
        </SheetHeader>
        <SheetBody>
          <ol className="flex flex-col text-13">
            {Array.from({ length: 40 }, (_, i) => (
              <li key={i} className="flex h-10 items-center justify-between border-b border-rule">
                <span className="font-mono text-12">evt_01JC8Z{String(i).padStart(4, '0')}</span>
                <span className="text-ink-2">200，耗时 {120 + i} 毫秒</span>
              </li>
            ))}
          </ol>
        </SheetBody>
      </SheetContent>
    </Sheet>
  ),
};
