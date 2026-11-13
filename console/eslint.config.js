import js from '@eslint/js';
import { defineConfig } from 'eslint/config';
import jsxA11y from 'eslint-plugin-jsx-a11y';
import reactHooks from 'eslint-plugin-react-hooks';
import storybook from 'eslint-plugin-storybook';
import globals from 'globals';
import tseslint from 'typescript-eslint';

import meridian from './eslint-rules/meridian.js';

/** 图标只用 Phosphor（经 src/components/ui/icon 封装）与自研 SVG；其他图标库一律禁止（frontend-design.md「图标」）。 */
const bannedIconLibraries = [
  { name: 'lucide-react', message: '图标只用 Phosphor（经 @/components/ui/icon），禁止 lucide-react。' },
  {
    name: '@radix-ui/react-icons',
    message: '图标只用 Phosphor（经 @/components/ui/icon），禁止 @radix-ui/react-icons。',
  },
  { name: 'react-icons', message: '图标只用 Phosphor（经 @/components/ui/icon）。' },
  { name: '@tabler/icons-react', message: '图标只用 Phosphor（经 @/components/ui/icon）。' },
];
const bannedIconPatterns = [
  {
    group: ['lucide-react/*', '@radix-ui/react-icons/*', 'react-icons/*', '@heroicons/*', '@tabler/*'],
    message: '图标只用 Phosphor（经 @/components/ui/icon）。',
  },
];
const phosphorOnlyThroughWrapper = {
  name: '@phosphor-icons/react',
  message: 'Phosphor 图标经 @/components/ui/icon 的 Icon 与 glyphs 使用，尺寸只有 16、20、24。',
};

export default defineConfig(
  {
    ignores: [
      'dist',
      'storybook-static',
      'coverage',
      'playwright-report',
      'test-results',
      'public/mockServiceWorker.js',
      'src/lib/api/v1.d.ts',
      '!.storybook',
    ],
  },
  js.configs.recommended,
  {
    files: ['**/*.{ts,tsx}'],
    extends: [tseslint.configs.recommendedTypeChecked],
    languageOptions: {
      parserOptions: { projectService: true, tsconfigRootDir: import.meta.dirname },
    },
    rules: {
      '@typescript-eslint/no-explicit-any': 'error',
      '@typescript-eslint/consistent-type-imports': 'error',
    },
  },
  {
    files: ['src/**/*.{ts,tsx}', 'mocks/**/*.{ts,tsx}', '.storybook/**/*.{ts,tsx}'],
    extends: [jsxA11y.flatConfigs.strict],
    plugins: { 'react-hooks': reactHooks, meridian },
    languageOptions: { globals: globals.browser },
    rules: {
      ...reactHooks.configs.recommended.rules,
      'meridian/no-raw-color': 'error',
      'meridian/no-banned-classes': 'error',
      'meridian/no-decorative-glyphs': 'error',
      'meridian/import-boundaries': 'error',
      'no-restricted-imports': [
        'error',
        { paths: [...bannedIconLibraries, phosphorOnlyThroughWrapper], patterns: bannedIconPatterns },
      ],
    },
  },
  {
    // Phosphor 的唯一入口：Icon 封装与图标清单。
    files: ['src/components/ui/icon.tsx', 'src/components/ui/glyphs.ts'],
    rules: {
      'no-restricted-imports': ['error', { paths: bannedIconLibraries, patterns: bannedIconPatterns }],
    },
  },
  {
    // 组件不直接调用 fetch：统一走 lib/api 与各领域的 api/ Hook（frontend-design.md「边界规则」）。
    files: ['src/components/**/*.tsx', 'src/features/**/components/**/*.tsx', 'src/prototypes/**/*.tsx'],
    rules: {
      'no-restricted-globals': [
        'error',
        { name: 'fetch', message: '组件不直接调用 fetch，走 lib/api 与领域的 api/ Hook。' },
      ],
    },
  },
  {
    // 令牌的测试与解析代码需要写出颜色字面量。
    files: ['src/**/*.test.{ts,tsx}', 'src/lib/color.ts'],
    rules: { 'meridian/no-raw-color': 'off' },
  },
  ...storybook.configs['flat/recommended'],
  {
    files: ['eslint-rules/**/*.js', 'scripts/**/*.mjs', 'eslint.config.js'],
    languageOptions: { globals: globals.node },
  },
  {
    files: ['vite.config.ts', 'playwright.config.ts', 'e2e/**/*.ts'],
    languageOptions: { globals: globals.node },
  },
);
