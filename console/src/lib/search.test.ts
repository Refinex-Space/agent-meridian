import { describe, expect, it } from 'vitest';

import { parseSearch, searchList, searchString, stringifySearch } from './search';

describe('搜索参数的格式', () => {
  it('重复的键解析为数组，单个的键解析为字符串', () => {
    expect(parseSearch('?status=running&status=idle&q=u_82&tab=all')).toEqual({
      status: ['running', 'idle'],
      q: 'u_82',
      tab: 'all',
    });
    expect(parseSearch('')).toEqual({});
  });

  it('数组展开为重复的键，空值不出现', () => {
    expect(stringifySearch({ status: ['running', 'idle'], q: 'ses_01', tab: undefined, x: '', y: [] })).toBe(
      '?status=running&status=idle&q=ses_01',
    );
    expect(stringifySearch({})).toBe('');
  });

  it('解析与序列化互逆（含中文与保留字符）', () => {
    const search = '?q=%E5%8D%8E%E4%B8%9C+%26+a%3Db&status=running&status=idle';
    expect(stringifySearch(parseSearch(search))).toBe(search);
  });

  it('读取单值与多值参数', () => {
    expect(searchString(['a', 'b'])).toBe('a');
    expect(searchString('')).toBeUndefined();
    expect(searchList('running')).toEqual(['running']);
    expect(searchList(undefined)).toEqual([]);
  });
});
