import { useState, type ComponentProps } from 'react';

import { Input } from '@/components/ui/input';

export interface NumberInputProps extends Omit<ComponentProps<typeof Input>, 'value' | 'onChange' | 'type'> {
  value: number | null;
  /** 空白为 null（可空字段表示"用默认值"；必填字段由校验报错），不是数字时为 NaN（由校验报错）。 */
  onChange: (value: number | null) => void;
  /** 允许小数（温度）；默认只接受整数的输入法提示。 */
  decimal?: boolean;
}

function parse(text: string): number | null {
  const t = text.trim();
  return t === '' ? null : Number(t);
}

/**
 * 数字输入框：保留用户正在输入的文字（"0." 不会被改写成 "0"），值由外部改变（放弃更改、读入草稿）时跟着更新。
 */
export function NumberInput({ value, onChange, decimal = false, ...props }: NumberInputProps) {
  const [text, setText] = useState(value === null ? '' : String(value));
  const parsed = parse(text);
  // 外部的值与输入框里的文字不再对应时（放弃更改），以外部为准；NaN 与 NaN 视为相同。
  const same = parsed === value || (Number.isNaN(parsed) && Number.isNaN(value));
  const shown = same ? text : value === null ? '' : String(value);
  return (
    <Input
      {...props}
      inputMode={decimal ? 'decimal' : 'numeric'}
      value={shown}
      onChange={(e) => {
        setText(e.target.value);
        onChange(parse(e.target.value));
      }}
    />
  );
}
