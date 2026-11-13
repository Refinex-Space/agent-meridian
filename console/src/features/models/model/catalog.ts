/**
 * 模型目录的文字与表单辅助：供应商类型、模型种类、能力、价格表的单价字段，以及数字输入的解析。
 */
import type { ModelKind, ProviderType, TokenPrices } from '@/lib/api/types';

export const PROVIDER_LABEL: Record<ProviderType, string> = {
  openai_compatible: 'OpenAI 兼容',
  anthropic: 'Anthropic',
  gemini: 'Gemini',
};

/** 端点的示例（占位文字）：OpenAI 兼容必填，其余类型留空用官方端点。 */
export const ENDPOINT_PLACEHOLDER: Record<ProviderType, string> = {
  openai_compatible: 'https://dashscope.aliyuncs.com/compatible-mode/v1',
  anthropic: '留空使用官方端点',
  gemini: '留空使用官方端点',
};

export const MODEL_KIND_LABEL: Record<ModelKind, string> = {
  chat: '对话',
  embedding: 'Embedding',
  rerank: '重排',
};

export const CAPABILITY_LABEL = {
  tool_calls: '工具调用',
  vision: '图片输入',
  reasoning: '推理内容',
} as const;

/** 价格表的单价字段（每百万 token），按账单上的顺序。 */
export const PRICE_FIELDS: readonly { key: keyof TokenPrices; label: string }[] = [
  { key: 'input', label: '输入' },
  { key: 'output', label: '输出' },
  { key: 'cache_read', label: '缓存读取' },
  { key: 'cache_write', label: '缓存写入' },
  { key: 'reasoning', label: '推理' },
];

/** 单价：十进制字符串，最多 12 位整数、6 位小数（管理 API 的 `TokenPrices`）。 */
export const PRICE = /^[0-9]{1,12}(\.[0-9]{1,6})?$/;

export const ZERO_PRICES: TokenPrices = {
  input: '0',
  output: '0',
  cache_read: '0',
  cache_write: '0',
  reasoning: '0',
};

/** 正整数的输入框：空串为 null，不是正整数时为 NaN（调用方据此报错）。 */
export function parsePositive(text: string): number | null {
  const trimmed = text.trim();
  if (!trimmed) return null;
  return /^[1-9][0-9]*$/.test(trimmed) ? Number(trimmed) : Number.NaN;
}

export const positiveText = (value: number | null | undefined) => (value == null ? '' : String(value));
