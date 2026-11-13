import type { Meta, StoryObj } from '@storybook/react-vite';
import type { ReactNode } from 'react';

import { Badge } from './badge';
import {
  Breadcrumb,
  BreadcrumbItem,
  BreadcrumbLink,
  BreadcrumbList,
  BreadcrumbPage,
  BreadcrumbSeparator,
} from './breadcrumb';
import { Button } from './button';
import { Checkbox } from './checkbox';
import { Command, CommandGroup, CommandInput, CommandItem, CommandList, CommandShortcut } from './command';
import { AgentGlyph, GaugeIcon, PlusIcon, ThreadGlyph, TrashIcon } from './glyphs';
import { Icon } from './icon';
import { Input } from './input';
import { Label } from './label';
import { RadioGroup, RadioGroupItem } from './radio-group';
import { Select, SelectTrigger, SelectValue } from './select';
import { Separator } from './separator';
import { Skeleton } from './skeleton';
import { Switch } from './switch';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from './table';
import { Tabs, TabsContent, TabsList, TabsTrigger } from './tabs';
import { Textarea } from './textarea';

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="flex flex-col gap-4">
      <h2 className="text-16 leading-heading font-semibold">{title}</h2>
      {children}
    </section>
  );
}

/** 全部基元放在一页，对照令牌页检查颜色、字体、间距、圆角是否同一套；浮层的样子见各自的"默认"故事。 */
function Overview() {
  return (
    <div className="flex min-h-screen flex-col gap-12 bg-surface p-8 text-ink">
      <h1 className="text-22 leading-heading font-semibold">基元总览</h1>

      <Section title="按钮与徽标">
        <div className="flex flex-wrap items-center gap-3">
          <Button variant="primary" icon={<Icon glyph={PlusIcon} />}>
            创建智能体
          </Button>
          <Button>编辑</Button>
          <Button variant="ghost">取消</Button>
          <Button variant="danger" icon={<Icon glyph={TrashIcon} />}>
            删除智能体
          </Button>
          <Button variant="primary" pending>
            保存中
          </Button>
          <Button disabled>不可用</Button>
          <Separator orientation="vertical" className="h-6" />
          <Badge>v12</Badge>
          <Badge tone="accent">默认环境</Badge>
          <Badge tone="brass">3</Badge>
          <Badge tone="danger">已停用</Badge>
        </div>
      </Section>

      <Section title="表单控件">
        <div className="grid max-w-220 grid-cols-2 gap-6">
          <div className="flex flex-col gap-1">
            <Label htmlFor="ov-name">名称</Label>
            <Input id="ov-name" defaultValue="经营分析助手" />
          </div>
          <div className="flex flex-col gap-1">
            <Label htmlFor="ov-model">模型</Label>
            <Select defaultValue="qwen-plus">
              <SelectTrigger id="ov-model">
                <SelectValue placeholder="选择模型" />
              </SelectTrigger>
            </Select>
          </div>
          <div className="flex flex-col gap-1">
            <Label htmlFor="ov-invalid">通知地址</Label>
            <Input
              id="ov-invalid"
              defaultValue="ops.example.cn/hooks"
              aria-invalid
              aria-describedby="ov-invalid-error"
            />
            <p id="ov-invalid-error" className="text-12 text-danger">
              地址要以 https:// 开头
            </p>
          </div>
          <div className="flex flex-col gap-1">
            <Label htmlFor="ov-readonly">智能体 ID</Label>
            <Input id="ov-readonly" defaultValue="agt_01JB7Q2M9XK4" readOnly />
          </div>
          <div className="col-span-2 flex flex-col gap-1">
            <Label htmlFor="ov-prompt">系统提示词</Label>
            <Textarea
              id="ov-prompt"
              defaultValue="你是华东大区的经营分析助手。回答前先检索知识库中的政策文件，引用时写明文件名与条款。"
            />
          </div>
          <div className="flex flex-col gap-3">
            <div className="flex items-center gap-2">
              <Checkbox id="ov-cb1" defaultChecked />
              <Label htmlFor="ov-cb1">执行 bash 命令</Label>
            </div>
            <div className="flex items-center gap-2">
              <Checkbox id="ov-cb2" checked="indeterminate" />
              <Label htmlFor="ov-cb2">访问外部网页</Label>
            </div>
            <div className="flex items-center gap-2">
              <Checkbox id="ov-cb3" disabled />
              <Label htmlFor="ov-cb3">写入工作区文件</Label>
            </div>
          </div>
          <div className="flex gap-8">
            <RadioGroup defaultValue="session" aria-label="记住此决定">
              {(
                [
                  ['none', '只这一次'],
                  ['session', '本会话内不再询问'],
                ] as const
              ).map(([value, label]) => (
                <div key={value} className="flex items-center gap-2">
                  <RadioGroupItem id={`ov-rg-${value}`} value={value} />
                  <Label htmlFor={`ov-rg-${value}`}>{label}</Label>
                </div>
              ))}
            </RadioGroup>
            <div className="flex flex-col gap-3">
              <div className="flex items-center gap-3">
                <Switch id="ov-sw1" defaultChecked />
                <Label htmlFor="ov-sw1">启用</Label>
              </div>
              <div className="flex items-center gap-3">
                <Switch id="ov-sw2" />
                <Label htmlFor="ov-sw2">暴露思考内容</Label>
              </div>
            </div>
          </div>
        </div>
      </Section>

      <Section title="导航与结构">
        <Breadcrumb>
          <BreadcrumbList>
            <BreadcrumbItem>
              <BreadcrumbLink href="#agents">智能体</BreadcrumbLink>
            </BreadcrumbItem>
            <BreadcrumbSeparator />
            <BreadcrumbItem>
              <BreadcrumbPage>经营分析助手</BreadcrumbPage>
            </BreadcrumbItem>
          </BreadcrumbList>
        </Breadcrumb>
        <Tabs defaultValue="summary" className="max-w-160">
          <TabsList>
            <TabsTrigger value="summary">概要</TabsTrigger>
            <TabsTrigger value="versions">版本</TabsTrigger>
            <TabsTrigger value="sessions">会话</TabsTrigger>
            <TabsTrigger value="permissions" disabled>
              权限
            </TabsTrigger>
          </TabsList>
          {['summary', 'versions', 'sessions', 'permissions'].map((value) => (
            <TabsContent key={value} value={value} className="pt-3 text-13 text-ink-2">
              标签页内容
            </TabsContent>
          ))}
        </Tabs>
        <Table className="max-w-200">
          <TableHeader>
            <TableRow className="hover:bg-transparent">
              <TableHead>会话</TableHead>
              <TableHead>智能体</TableHead>
              <TableHead className="text-right">token</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            <TableRow>
              <TableCell className="font-mono text-12">ses_01JC8Z4V7Q</TableCell>
              <TableCell>经营分析助手</TableCell>
              <TableCell className="text-right">48,210</TableCell>
            </TableRow>
            <TableRow data-state="selected">
              <TableCell className="font-mono text-12">ses_01JC8W2KX1</TableCell>
              <TableCell>客服质检</TableCell>
              <TableCell className="text-right">6,104</TableCell>
            </TableRow>
            <TableRow>
              <TableCell>
                <Skeleton className="h-3 w-28" />
              </TableCell>
              <TableCell>
                <Skeleton className="h-3 w-24" />
              </TableCell>
              <TableCell className="text-right">
                <Skeleton className="ml-auto h-3 w-12" />
              </TableCell>
            </TableRow>
          </TableBody>
        </Table>
      </Section>

      <Section title="命令面板">
        <Command className="shadow-overlay w-140 rounded-overlay border border-rule">
          <CommandInput placeholder="搜索页面、智能体、会话 ID" />
          <CommandList>
            <CommandGroup heading="页面">
              <CommandItem>
                <Icon glyph={GaugeIcon} />
                概览
              </CommandItem>
              <CommandItem>
                <Icon glyph={ThreadGlyph} />
                会话
                <CommandShortcut>G S</CommandShortcut>
              </CommandItem>
              <CommandItem>
                <Icon glyph={AgentGlyph} />
                经营分析助手
              </CommandItem>
            </CommandGroup>
          </CommandList>
        </Command>
      </Section>
    </div>
  );
}

const meta = {
  title: '基元/总览',
  component: Overview,
} satisfies Meta<typeof Overview>;

export default meta;

export const All: StoryObj<typeof meta> = { name: '总览' };
