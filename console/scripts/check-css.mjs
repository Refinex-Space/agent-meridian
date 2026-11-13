// 样式表的守护检查（frontend-design.md「禁用清单」中能机检的条目）：
// - 颜色字面量（十六进制、颜色函数）只能出现在 src/styles/tokens.css；
// - 任何样式表都不能有渐变、backdrop-filter、letter-spacing（字距令牌已全部移除）。
// 发现问题时逐条输出"文件:行  原因"，以 1 退出。
import { readdirSync, readFileSync } from 'node:fs';
import path from 'node:path';

const root = path.resolve(import.meta.dirname, '..');
const TOKENS = 'src/styles/tokens.css';

const checks = [
  {
    pattern: /#(?:[0-9a-f]{3,4}|[0-9a-f]{6}|[0-9a-f]{8})\b/i,
    reason: '颜色字面量只能写在 tokens.css',
    tokensAllowed: true,
  },
  {
    pattern: /\b(?:rgba?|hsla?|hwb|lab|lch|oklab|oklch)\(/i,
    reason: '颜色函数只能写在 tokens.css',
    tokensAllowed: true,
  },
  { pattern: /(?:linear|radial|conic)-gradient\(/i, reason: '禁止渐变（禁用清单第 2 条）' },
  { pattern: /backdrop-filter|@apply[^;]*\bbackdrop-/i, reason: '禁止背景模糊（禁用清单第 3 条）' },
  { pattern: /letter-spacing|@apply[^;]*\btracking-/i, reason: '禁止字距（禁用清单第 10 条）' },
];

function* cssFiles(dir) {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) yield* cssFiles(full);
    else if (entry.name.endsWith('.css')) yield full;
  }
}

const problems = [];
for (const file of cssFiles(path.join(root, 'src'))) {
  const relative = path.relative(root, file).split(path.sep).join('/');
  const lines = readFileSync(file, 'utf8').split('\n');
  lines.forEach((raw, index) => {
    const line = raw.replace(/\/\*.*?\*\//g, '');
    for (const { pattern, reason, tokensAllowed } of checks) {
      if (tokensAllowed && relative === TOKENS) continue;
      if (pattern.test(line)) problems.push(`${relative}:${index + 1}  ${reason}：${raw.trim()}`);
    }
  });
}

if (problems.length > 0) {
  console.error(problems.join('\n'));
  process.exit(1);
}
