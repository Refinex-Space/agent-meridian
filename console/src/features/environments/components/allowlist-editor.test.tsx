import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { describe, expect, it } from 'vitest';

import { AllowlistEditor } from './allowlist-editor';

function Harness({ initial = [] as string[] }) {
  const [value, setValue] = useState(initial);
  return <AllowlistEditor id="domain" value={value} onChange={setValue} itemErrors={{}} />;
}

const input = () => screen.getByRole('textbox');
const list = () => screen.getByRole('list', { name: '出网白名单' });

describe('AllowlistEditor（T22-A05）', () => {
  it('不合法的域名就地报错，不加入白名单', async () => {
    const user = userEvent.setup();
    render(<Harness />);
    await user.type(input(), 'https://pypi.org/simple{Enter}');
    expect(screen.getByText(/不带协议与路径/)).toBeInTheDocument();
    expect(input()).toBeInvalid();
    expect(within(list()).getByText('没有允许的域名：沙箱不能访问外网')).toBeInTheDocument();
  });

  it('粘贴多个域名一次加入，转小写，已有的提示重复', async () => {
    const user = userEvent.setup();
    render(<Harness initial={['pypi.org']} />);
    await user.click(input());
    await user.paste('*.Example.com, files.example.com');
    await user.click(screen.getByRole('button', { name: '添加' }));
    expect(
      within(list())
        .getAllByRole('listitem')
        .map((li) => li.textContent),
    ).toEqual(['pypi.org', '*.example.com', 'files.example.com']);
    expect(screen.getByText('3 / 100')).toBeInTheDocument();
    await user.type(input(), 'PYPI.ORG{Enter}');
    expect(screen.getByText('这些域名已经在白名单里。')).toBeInTheDocument();
  });

  it('移除一个域名', async () => {
    const user = userEvent.setup();
    render(<Harness initial={['pypi.org', 'bi.example.com']} />);
    await user.click(screen.getByRole('button', { name: '移除 pypi.org' }));
    expect(within(list()).getAllByRole('listitem')).toHaveLength(1);
  });
});
