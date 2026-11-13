/**
 * 控制台的守护规则：`docs/engineering/frontend-design.md`「禁用清单」中能机检的条目、令牌约束与导入边界。
 *
 * - `no-raw-color`：源码中出现十六进制颜色、颜色函数（含 Tailwind 任意色值）。颜色只能来自 tokens.css。
 * - `no-banned-classes`：渐变、背景模糊、`uppercase` 与 `tracking-*` 同时使用、按钮上的 `rounded-full`、
 *   不在 4px 阶梯（0、1、2、3、4、6、8、12）上的内外边距与间隙。
 * - `no-decorative-glyphs`：界面文字（JSX 文本与属性）中的 emoji 与箭头"→"。
 * - `import-boundaries`：目录分层与"跨领域只经 index.ts"。
 */
import path from 'node:path';

const HEX_COLOR = /(^|[^\w&#/-])#(?:[0-9a-f]{3,4}|[0-9a-f]{6}|[0-9a-f]{8})(?![\w-])/i;
const COLOR_FUNCTION = /\b(?:rgba?|hsla?|hwb|lab|lch|oklab|oklch|color|color-mix|light-dark)\(/i;
const GRADIENT_FUNCTION = /(?:linear|radial|conic)-gradient\(/i;
// Extended_Pictographic 包含 ©、®、™，它们不是装饰。
const EMOJI = /(?![©®™])\p{Extended_Pictographic}/u;
const ARROWS = /[→⟶➔➜➝➞➡⇒]/;

const CLASS_FUNCTIONS = new Set(['cn', 'cva', 'clsx', 'cx', 'twMerge']);
// 较长的名字在前：否则 `gap-x-2` 会被当成 `gap` 加值 `x-2`。
const SPACING_PROPERTY =
  /^-?(?:gap-x|gap-y|space-x|space-y|px|py|ps|pe|pt|pr|pb|pl|mx|my|ms|me|mt|mr|mb|ml|gap|p|m)-(.+)$/;
const SPACING_ALLOWED = new Set(['0', 'px', '1', '2', '3', '4', '6', '8', '12', 'auto']);

/** 去掉变体前缀（`hover:`、`md:`、`[&>svg]:`）与重要性标记，得到工具类本身。 */
function utilityOf(token) {
  let depth = 0;
  let start = 0;
  for (let i = 0; i < token.length; i += 1) {
    const ch = token[i];
    if (ch === '[') depth += 1;
    else if (ch === ']') depth -= 1;
    else if (ch === ':' && depth === 0) start = i + 1;
  }
  return token.slice(start).replace(/^!|!$/g, '');
}

function classTokens(text) {
  return text.split(/\s+/).filter(Boolean).map(utilityOf);
}

/** 收集一个表达式中所有可能成为类名的字符串片段（字面量、模板、条件、逻辑、数组、对象的键与值）。 */
function collectStrings(node, out = []) {
  if (!node) return out;
  switch (node.type) {
    case 'Literal':
      if (typeof node.value === 'string') out.push({ text: node.value, node });
      break;
    case 'TemplateLiteral':
      for (const quasi of node.quasis) out.push({ text: quasi.value.cooked ?? '', node: quasi });
      for (const expr of node.expressions) collectStrings(expr, out);
      break;
    case 'ConditionalExpression':
      collectStrings(node.consequent, out);
      collectStrings(node.alternate, out);
      break;
    case 'LogicalExpression':
      collectStrings(node.left, out);
      collectStrings(node.right, out);
      break;
    case 'ArrayExpression':
      for (const el of node.elements) collectStrings(el, out);
      break;
    case 'ObjectExpression':
      for (const property of node.properties) {
        if (property.type !== 'Property') continue;
        if (
          property.key.type === 'Literal' ||
          (property.computed && property.key.type === 'TemplateLiteral')
        ) {
          collectStrings(property.key, out);
        }
        collectStrings(property.value, out);
      }
      break;
    case 'CallExpression':
      if (isClassFunction(node.callee)) for (const arg of node.arguments) collectStrings(arg, out);
      break;
    case 'JSXExpressionContainer':
      collectStrings(node.expression, out);
      break;
    case 'TSAsExpression':
    case 'TSSatisfiesExpression':
      collectStrings(node.expression, out);
      break;
    default:
      break;
  }
  return out;
}

function isClassFunction(callee) {
  return callee.type === 'Identifier' && CLASS_FUNCTIONS.has(callee.name);
}

function jsxName(name) {
  if (name.type === 'JSXIdentifier') return name.name;
  if (name.type === 'JSXMemberExpression') return name.property.name;
  return '';
}

function attribute(opening, attrName) {
  return opening.attributes.find(
    (attr) =>
      attr.type === 'JSXAttribute' && attr.name.type === 'JSXIdentifier' && attr.name.name === attrName,
  );
}

function isButtonElement(opening) {
  const name = jsxName(opening.name);
  if (name === 'button' || /Button$/.test(name)) return true;
  const role = attribute(opening, 'role');
  return role?.value?.type === 'Literal' && role.value.value === 'button';
}

const noRawColor = {
  meta: {
    type: 'problem',
    docs: { description: '颜色只能来自令牌：禁止十六进制颜色、颜色函数与 Tailwind 任意色值' },
    messages: {
      hex: '禁止十六进制颜色"{{value}}"：颜色只能来自 tokens.css 的令牌（bg-surface、text-ink 等）。',
      fn: '禁止颜色函数"{{value}}"：颜色只能来自 tokens.css 的令牌。',
    },
    schema: [],
  },
  create(context) {
    function check(node, text) {
      const hex = HEX_COLOR.exec(text);
      if (hex) context.report({ node, messageId: 'hex', data: { value: hex[0].slice(hex[0].indexOf('#')) } });
      const fn = COLOR_FUNCTION.exec(text);
      if (fn) context.report({ node, messageId: 'fn', data: { value: fn[0] } });
    }
    return {
      Literal(node) {
        if (typeof node.value === 'string') check(node, node.value);
      },
      TemplateElement(node) {
        check(node, node.value.cooked ?? '');
      },
      JSXText(node) {
        check(node, node.value);
      },
    };
  },
};

const noBannedClasses = {
  meta: {
    type: 'problem',
    docs: { description: '禁用清单中能机检的样式：渐变、背景模糊、全大写加字距、胶囊按钮、阶梯外的间距' },
    messages: {
      gradient: '禁止渐变"{{value}}"（禁用清单第 2 条）。',
      backdrop: '禁止背景模糊与 backdrop 滤镜"{{value}}"（禁用清单第 3 条）。',
      eyebrow: '禁止 uppercase 与 tracking-* 同时使用（禁用清单第 10 条）：层级靠字号、字重与颜色。',
      pillButton: '按钮禁止 rounded-full（禁用清单第 7 条）：按钮用 rounded-control。',
      spacing:
        '间距"{{value}}"不在 4px 阶梯上：内外边距与间隙只用 0、px、1、2、3、4、6、8、12（4 至 48px）。',
    },
    schema: [],
  },
  create(context) {
    function checkClassList(fragments) {
      let upper = null;
      let tracking = null;
      for (const { text, node } of fragments) {
        for (const utility of classTokens(text)) {
          if (/^(?:bg-(?:gradient|linear|radial|conic)(?:-|$)|from-|via-|to-)/.test(utility)) {
            context.report({ node, messageId: 'gradient', data: { value: utility } });
          } else if (utility.startsWith('backdrop-')) {
            context.report({ node, messageId: 'backdrop', data: { value: utility } });
          } else if (utility === 'uppercase') {
            upper = node;
          } else if (utility.startsWith('tracking-')) {
            tracking = node;
          } else {
            const spacing = SPACING_PROPERTY.exec(utility);
            if (spacing && !SPACING_ALLOWED.has(spacing[1])) {
              context.report({ node, messageId: 'spacing', data: { value: utility } });
            }
          }
        }
      }
      if (upper && tracking) context.report({ node: tracking, messageId: 'eyebrow' });
    }

    function hasRoundedFull(fragments) {
      return fragments.find(({ text }) => classTokens(text).includes('rounded-full'));
    }

    return {
      Literal(node) {
        if (typeof node.value !== 'string') return;
        if (GRADIENT_FUNCTION.test(node.value)) {
          context.report({
            node,
            messageId: 'gradient',
            data: { value: GRADIENT_FUNCTION.exec(node.value)[0] },
          });
        }
        if (/backdrop-filter/i.test(node.value)) {
          context.report({ node, messageId: 'backdrop', data: { value: 'backdrop-filter' } });
        }
      },
      TemplateElement(node) {
        const text = node.value.cooked ?? '';
        if (GRADIENT_FUNCTION.test(text)) {
          context.report({
            node,
            messageId: 'gradient',
            data: { value: GRADIENT_FUNCTION.exec(text)[0] },
          });
        }
      },
      Property(node) {
        const key = node.key.type === 'Identifier' ? node.key.name : node.key.value;
        if (key === 'backdropFilter' || key === 'WebkitBackdropFilter') {
          context.report({ node, messageId: 'backdrop', data: { value: key } });
        }
      },
      JSXAttribute(node) {
        if (node.name.type !== 'JSXIdentifier' || !['className', 'class'].includes(node.name.name)) return;
        checkClassList(collectStrings(node.value));
      },
      CallExpression(node) {
        if (!isClassFunction(node.callee)) return;
        // 嵌套在另一个类名函数或 className 里的调用由外层统一检查，避免重复报告。
        const parent = node.parent;
        if (parent?.type === 'CallExpression' && isClassFunction(parent.callee)) return;
        if (parent?.type === 'JSXExpressionContainer' && parent.parent?.type === 'JSXAttribute') return;
        const fragments = node.arguments.flatMap((arg) => collectStrings(arg));
        checkClassList(fragments);
        if (
          node.callee.name === 'cva' &&
          parent?.type === 'VariableDeclarator' &&
          parent.id.type === 'Identifier' &&
          /button/i.test(parent.id.name)
        ) {
          const hit = hasRoundedFull(fragments);
          if (hit) context.report({ node: hit.node, messageId: 'pillButton' });
        }
      },
      JSXOpeningElement(node) {
        if (!isButtonElement(node)) return;
        const cls = attribute(node, 'className');
        if (!cls) return;
        const hit = hasRoundedFull(collectStrings(cls.value));
        if (hit) context.report({ node: hit.node, messageId: 'pillButton' });
      },
    };
  },
};

const noDecorativeGlyphs = {
  meta: {
    type: 'problem',
    docs: { description: '界面文字中禁止 emoji 与"→"（禁用清单第 8、12 条）' },
    messages: {
      emoji: '界面文字中禁止 emoji"{{value}}"（禁用清单第 8 条）。',
      arrow: '界面文字中禁止"{{value}}"（禁用清单第 12 条）：按钮与链接文字写清动作即可。',
    },
    schema: [],
  },
  create(context) {
    function check(node, text) {
      const emoji = EMOJI.exec(text);
      if (emoji) context.report({ node, messageId: 'emoji', data: { value: emoji[0] } });
      const arrow = ARROWS.exec(text);
      if (arrow) context.report({ node, messageId: 'arrow', data: { value: arrow[0] } });
    }
    function inJsx(node) {
      const parent = node.parent;
      return parent?.type === 'JSXAttribute' || parent?.type === 'JSXExpressionContainer';
    }
    return {
      JSXText(node) {
        check(node, node.value);
      },
      Literal(node) {
        if (typeof node.value === 'string' && inJsx(node)) check(node, node.value);
      },
      TemplateLiteral(node) {
        if (inJsx(node)) for (const quasi of node.quasis) check(quasi, quasi.value.cooked ?? '');
      },
    };
  },
};

/**
 * 目录分层（frontend-design.md「边界规则」）：键是导入方所在层，值是允许导入的层。
 * `feature` 之间只能经对方的 `index.ts`；故事与测试另外可以导入 `mocks`、`test` 与底层（ui、lib、assets、styles）。
 * `app` 是正式应用（路由、外壳、Provider），不依赖原型与 MSW；原型可以借用 `app` 的外壳，T25b 之后随原型一起删除。
 */
const LAYER_RULES = {
  ui: ['ui', 'lib', 'assets', 'styles'],
  domain: ['domain', 'ui', 'lib', 'assets', 'styles'],
  template: ['template', 'domain', 'ui', 'lib', 'assets', 'styles'],
  feature: ['feature', 'template', 'domain', 'ui', 'lib', 'assets', 'styles'],
  lib: ['lib'],
  assets: ['assets'],
  prototype: ['prototype', 'app', 'feature', 'template', 'domain', 'ui', 'lib', 'assets', 'styles', 'mocks'],
  app: ['app', 'feature', 'template', 'domain', 'ui', 'lib', 'assets', 'styles'],
  mocks: ['mocks', 'lib'],
  test: ['test', 'lib', 'mocks'],
};

function layerOf(relative) {
  const parts = relative.split('/');
  if (parts[0] === 'mocks') return { layer: 'mocks' };
  if (parts[0] !== 'src') return null;
  switch (parts[1]) {
    case 'components':
      if (parts[2] === 'ui') return { layer: 'ui' };
      if (parts[2] === 'domain') return { layer: 'domain' };
      if (parts[2] === 'templates') return { layer: 'template' };
      return null;
    case 'features':
      return { layer: 'feature', feature: parts[2], rest: parts.slice(3).join('/') };
    case 'lib':
    case 'assets':
    case 'styles':
    case 'app':
    case 'test':
      return { layer: parts[1] };
    case 'prototypes':
      return { layer: 'prototype' };
    default:
      return null;
  }
}

const importBoundaries = {
  meta: {
    type: 'problem',
    docs: { description: '目录分层与跨领域导入只经 index.ts' },
    messages: {
      layer: '{{from}} 不能导入 {{to}}（{{target}}）：分层规则见 frontend-design.md「边界规则」。',
      featureInternal:
        '不能导入领域 {{feature}} 的内部文件（{{target}}）：跨领域只经 features/{{feature}}/index.ts，复用的东西移到 components/domain 或 lib。',
    },
    schema: [],
  },
  create(context) {
    const root = context.cwd;
    const filename = context.filename;
    const fromRelative = path.relative(root, filename).split(path.sep).join('/');
    const from = layerOf(fromRelative);
    if (!from) return {};
    const isStoryOrTest = /\.(stories|test)\.tsx?$/.test(filename);

    function resolve(source) {
      if (source.startsWith('@/')) return path.join(root, 'src', source.slice(2));
      if (source.startsWith('@mocks/')) return path.join(root, 'mocks', source.slice(7));
      if (source.startsWith('.')) return path.resolve(path.dirname(filename), source);
      return null;
    }

    function check(node, source) {
      if (typeof source !== 'string') return;
      const resolved = resolve(source.replace(/\?.*$/, ''));
      if (!resolved) return;
      const targetRelative = path.relative(root, resolved).split(path.sep).join('/');
      const to = layerOf(targetRelative);
      if (!to) return;
      if (to.layer === 'feature' && !(from.layer === 'feature' && from.feature === to.feature)) {
        const rest = to.rest.replace(/\.(tsx?|jsx?)$/, '');
        if (rest !== '' && rest !== 'index') {
          context.report({
            node,
            messageId: 'featureInternal',
            data: { feature: to.feature, target: source },
          });
          return;
        }
      }
      const allowed = new Set(LAYER_RULES[from.layer] ?? []);
      if (isStoryOrTest) {
        // 故事与测试可以导入夹具与底层的基础设施，令牌页、图标页就放在它们展示的目录里。
        for (const layer of ['mocks', 'test', 'ui', 'lib', 'assets', 'styles']) allowed.add(layer);
      }
      if (!allowed.has(to.layer)) {
        context.report({
          node,
          messageId: 'layer',
          data: { from: from.layer, to: to.layer, target: source },
        });
      }
    }

    return {
      ImportDeclaration(node) {
        check(node.source, node.source.value);
      },
      ExportNamedDeclaration(node) {
        if (node.source) check(node.source, node.source.value);
      },
      ExportAllDeclaration(node) {
        check(node.source, node.source.value);
      },
      ImportExpression(node) {
        if (node.source.type === 'Literal') check(node.source, node.source.value);
      },
    };
  },
};

export default {
  meta: { name: 'meridian' },
  rules: {
    'no-raw-color': noRawColor,
    'no-banned-classes': noBannedClasses,
    'no-decorative-glyphs': noDecorativeGlyphs,
    'import-boundaries': importBoundaries,
  },
};
