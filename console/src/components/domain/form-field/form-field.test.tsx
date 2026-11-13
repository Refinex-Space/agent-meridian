import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';

import { Input } from '@/components/ui/input';
import { TooltipProvider } from '@/components/ui/tooltip';

import { fieldAria, FormField } from './form-field';

describe('FormField', () => {
  it('标签标注控件，错误经 aria-describedby 关联', () => {
    const error = '温度在 0 到 2 之间。';
    render(
      <FormField id="temperature" label="温度" error={error}>
        <Input id="temperature" {...fieldAria('temperature', error)} />
      </FormField>,
    );
    const input = screen.getByRole('textbox', { name: '温度' });
    expect(input).toBeInvalid();
    expect(input).toHaveAccessibleDescription(error);
  });

  it('没有错误时不渲染错误文字，也不标记控件', () => {
    render(
      <FormField id="temperature" label="温度">
        <Input id="temperature" {...fieldAria('temperature', null)} />
      </FormField>,
    );
    expect(screen.getByRole('textbox', { name: '温度' })).not.toHaveAttribute('aria-invalid');
  });

  it('补充说明只在 info 提示里', async () => {
    render(
      <TooltipProvider delayDuration={0}>
        <FormField id="temperature" label="温度" hint="0 到 2；分析类任务宜低">
          <Input id="temperature" />
        </FormField>
      </TooltipProvider>,
    );
    expect(screen.queryByText('0 到 2；分析类任务宜低')).toBeNull();
    await userEvent.hover(screen.getByRole('button', { name: '温度的说明' }));
    expect((await screen.findAllByText('0 到 2；分析类任务宜低')).length).toBeGreaterThan(0);
  });

  it('控件组的标签是 span，组用 aria-labelledby 引用', () => {
    render(
      <FormField id="tools" label="平台工具" group>
        <div role="group" aria-labelledby="tools-label" />
      </FormField>,
    );
    expect(screen.getByRole('group', { name: '平台工具' })).toBeInTheDocument();
  });
});
