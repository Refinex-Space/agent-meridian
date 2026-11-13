import path from 'node:path';

import { RuleTester } from 'eslint';
import tseslint from 'typescript-eslint';
import { afterAll, describe, it } from 'vitest';

import meridian from './meridian.js';

RuleTester.afterAll = afterAll;
RuleTester.describe = describe;
RuleTester.it = it;
RuleTester.itOnly = it.only;

const tester = new RuleTester({
  languageOptions: {
    parser: tseslint.parser,
    parserOptions: { ecmaFeatures: { jsx: true } },
  },
});

const root = process.cwd();
const file = (relative) => path.join(root, relative);
const tsx = file('src/components/domain/example/example.tsx');

tester.run('no-raw-color', meridian.rules['no-raw-color'], {
  valid: [
    { code: '<div className="bg-surface text-ink border-rule" />', filename: tsx },
    { code: "const href = '#main';", filename: tsx },
    { code: '<div style={{ color: "var(--color-ink)" }} />', filename: tsx },
  ],
  invalid: [
    { code: '<div className="bg-[#fff]" />', filename: tsx, errors: [{ messageId: 'hex' }] },
    { code: "<div style={{ color: '#185C9E' }} />", filename: tsx, errors: [{ messageId: 'hex' }] },
    { code: "const shadow = 'rgb(0 0 0 / 0.1)';", filename: tsx, errors: [{ messageId: 'fn' }] },
    { code: '<p className="text-[oklch(0.5_0.1_250)]" />', filename: tsx, errors: [{ messageId: 'fn' }] },
    {
      code: '<p className="bg-[#fff] text-[rgb(0,0,0)]" />',
      filename: tsx,
      errors: [{ messageId: 'hex', data: { value: '#fff' } }, { messageId: 'fn' }],
    },
  ],
});

tester.run('no-banned-classes', meridian.rules['no-banned-classes'], {
  valid: [
    { code: '<div className="gap-x-2 gap-y-1 px-3 py-2 mt-1 -mb-px space-y-4 p-0 m-auto" />', filename: tsx },
    { code: '<span className="uppercase" />', filename: tsx },
    { code: '<span className="block size-3 rounded-full bg-brass" />', filename: tsx },
    { code: "cn('p-4', active && 'gap-6', { 'pt-12': wide })", filename: tsx },
    { code: "const labelVariants = cva('rounded-full px-2');", filename: tsx },
  ],
  invalid: [
    {
      code: '<div className="bg-gradient-to-r from-indigo-500 to-pink-500" />',
      filename: tsx,
      errors: [{ messageId: 'gradient' }, { messageId: 'gradient' }, { messageId: 'gradient' }],
    },
    { code: '<div className="bg-linear-to-r" />', filename: tsx, errors: [{ messageId: 'gradient' }] },
    { code: '<div className="backdrop-blur-md" />', filename: tsx, errors: [{ messageId: 'backdrop' }] },
    {
      code: "<div style={{ backgroundImage: 'linear-gradient(90deg, red, blue)' }} />",
      filename: tsx,
      errors: [{ messageId: 'gradient' }],
    },
    {
      code: "<div style={{ backdropFilter: 'blur(4px)' }} />",
      filename: tsx,
      errors: [{ messageId: 'backdrop' }],
    },
    {
      code: "<h3 className={cn('uppercase', 'tracking-wide')} />",
      filename: tsx,
      errors: [{ messageId: 'eyebrow' }],
    },
    {
      code: '<h3 className="text-12 uppercase tracking-[0.2em]" />',
      filename: tsx,
      errors: [{ messageId: 'eyebrow' }],
    },
    {
      code: '<button className="rounded-full px-3" />',
      filename: tsx,
      errors: [{ messageId: 'pillButton' }],
    },
    { code: '<Button className="rounded-full" />', filename: tsx, errors: [{ messageId: 'pillButton' }] },
    {
      code: '<div role="button" className="rounded-full" />',
      filename: tsx,
      errors: [{ messageId: 'pillButton' }],
    },
    {
      code: "const buttonVariants = cva('inline-flex rounded-full px-3');",
      filename: tsx,
      errors: [{ messageId: 'pillButton' }],
    },
    {
      code: '<div className="p-5 gap-7 m-[13px] hover:px-10" />',
      filename: tsx,
      errors: [
        { messageId: 'spacing' },
        { messageId: 'spacing' },
        { messageId: 'spacing' },
        { messageId: 'spacing' },
      ],
    },
  ],
});

tester.run('no-decorative-glyphs', meridian.rules['no-decorative-glyphs'], {
  valid: [
    { code: '<span>发布版本</span>', filename: tsx },
    { code: '<kbd>⌘K</kbd>', filename: tsx },
    { code: '<span>© 2026</span>', filename: tsx },
    { code: "const note = '内部常量，不出现在界面上';", filename: tsx },
  ],
  invalid: [
    { code: '<span>部署完成 ✨</span>', filename: tsx, errors: [{ messageId: 'emoji' }] },
    { code: '<a href="/agents">查看详情 →</a>', filename: tsx, errors: [{ messageId: 'arrow' }] },
    { code: '<Button label="下一步 →" />', filename: tsx, errors: [{ messageId: 'arrow' }] },
    { code: "<span>{'🚀 发布'}</span>", filename: tsx, errors: [{ messageId: 'emoji' }] },
    { code: '<span>{`已复制 ✅`}</span>', filename: tsx, errors: [{ messageId: 'emoji' }] },
  ],
});

tester.run('import-boundaries', meridian.rules['import-boundaries'], {
  valid: [
    { code: "import { cn } from '@/lib/cn';", filename: file('src/components/ui/button.tsx') },
    {
      code: "import { Button } from '@/components/ui/button';",
      filename: file('src/components/domain/x/x.tsx'),
    },
    {
      code: "import { SessionList } from '@/features/sessions';",
      filename: file('src/features/agents/routes/list.tsx'),
    },
    {
      code: "import { useAgents } from '../api/use-agents';",
      filename: file('src/features/agents/routes/list.tsx'),
    },
    {
      code: "import { q3Handlers } from '@mocks/handlers/q3';",
      filename: file('src/components/ui/button.stories.tsx'),
    },
    { code: "import { Icon } from '@/components/ui/icon';", filename: file('src/styles/tokens.stories.tsx') },
    { code: "import { useQuery } from '@tanstack/react-query';", filename: file('src/lib/query.ts') },
  ],
  invalid: [
    {
      code: "import { SessionTimeline } from '@/components/domain/session-timeline';",
      filename: file('src/components/ui/tabs.tsx'),
      errors: [{ messageId: 'layer' }],
    },
    {
      code: "import { Button } from '../components/ui/button';",
      filename: file('src/lib/format.ts'),
      errors: [{ messageId: 'layer' }],
    },
    {
      code: "import { SessionRow } from '@/features/sessions/components/session-row';",
      filename: file('src/features/agents/routes/list.tsx'),
      errors: [{ messageId: 'featureInternal' }],
    },
    {
      code: "import { useSessions } from '../../sessions/api/use-sessions';",
      filename: file('src/features/agents/routes/list.tsx'),
      errors: [{ messageId: 'featureInternal' }],
    },
    {
      code: "import { AgentForm } from '@/features/agents';",
      filename: file('src/components/domain/x/x.tsx'),
      errors: [{ messageId: 'layer' }],
    },
    {
      code: "import { Button } from '@/components/ui/button';",
      filename: file('mocks/handlers/q3.ts'),
      errors: [{ messageId: 'layer' }],
    },
  ],
});
