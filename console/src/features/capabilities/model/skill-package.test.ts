import { describe, expect, it } from 'vitest';

import {
  readSkillPackage,
  splitFrontmatter,
  stripCommonRoot,
  toPackage,
  uploadable,
  validPath,
  type PickedFile,
} from './skill-package';

const encoder = new TextEncoder();
const file = (path: string, text: string): PickedFile => ({ path, bytes: encoder.encode(text) });

const SKILL_MD = `---
name: docx
description: >
  把 Markdown 转成 Word 文档，
  保留标题与表格。
license: Apache-2.0
allowed-tools:
  - bash
---

# Markdown 转 Word

运行 scripts/md_to_docx.py。
`;

describe('技能包的解析与校验', () => {
  it('选择文件夹时去掉共同的顶层文件夹，忽略隐藏文件，按路径排序', () => {
    const draft = readSkillPackage([
      file('docx/scripts/md_to_docx.py', 'import sys\n'),
      file('docx/SKILL.md', SKILL_MD),
      file('docx/.DS_Store', '\u0000\u0001'),
      file('docx/.git/config', '[core]'),
    ]);
    expect(draft.files.map((f) => f.path)).toEqual(['SKILL.md', 'scripts/md_to_docx.py']);
    expect(draft.ignored).toEqual(['docx/.DS_Store', 'docx/.git/config']);
    expect(uploadable(draft)).toBe(true);
    expect(toPackage(draft).files.map((f) => f.path)).toEqual(['SKILL.md', 'scripts/md_to_docx.py']);
  });

  it('解析 frontmatter：折叠块、其余键原样保留，正文是结束标记之后的内容', () => {
    const draft = readSkillPackage([file('SKILL.md', SKILL_MD)]);
    expect(draft.name).toBe('docx');
    expect(draft.description).toBe('把 Markdown 转成 Word 文档， 保留标题与表格。');
    expect(draft.extra).toEqual({ license: 'Apache-2.0', 'allowed-tools': ['bash'] });
    expect(draft.body).toBe('# Markdown 转 Word\n\n运行 scripts/md_to_docx.py。\n');
    expect(draft.errors).toEqual([]);
  });

  it('与服务端相同的整体规则：缺少 SKILL.md、frontmatter 不合法、name 不合法、缺 description', () => {
    expect(readSkillPackage([file('README.md', '# 说明')]).errors).toEqual([
      '技能包必须在根目录包含 SKILL.md',
    ]);
    expect(readSkillPackage([file('SKILL.md', '# 没有 frontmatter')]).errors).toEqual([
      'SKILL.md 必须以 --- 包围的 YAML frontmatter 开头',
    ]);
    expect(readSkillPackage([file('SKILL.md', '---\nname: [a\n---\n')]).errors).toEqual([
      'SKILL.md 的 frontmatter 不是合法的 YAML',
    ]);
    expect(readSkillPackage([file('SKILL.md', '---\n- a\n---\n')]).errors).toEqual([
      'SKILL.md 的 frontmatter 必须是映射',
    ]);
    const bad = readSkillPackage([file('SKILL.md', '---\nname: Docx Tool\n---\n')]);
    expect(bad.errors).toEqual([
      'SKILL.md 的 frontmatter 缺少合法的 name（小写字母、数字、短横线，至多 64 个字符）',
      'SKILL.md 的 frontmatter 缺少 description，或超过 1024 个字符',
    ]);
    expect(uploadable(bad)).toBe(false);
  });

  it('新版本要求 name 与技能名一致', () => {
    const draft = readSkillPackage([file('SKILL.md', SKILL_MD)], 'pdf');
    expect(draft.errors).toEqual(['新版本的 name 必须是 pdf，现在是 docx']);
  });

  it('逐个文件的问题：不是 UTF-8、超过 1 MB；合计超过 5 MB、超过 100 个文件', () => {
    const binary: PickedFile = { path: 'logo.png', bytes: new Uint8Array([0x89, 0x50, 0xff, 0xfe]) };
    const big = file('data.txt', 'a'.repeat(1024 * 1024 + 1));
    const draft = readSkillPackage([file('SKILL.md', SKILL_MD), binary, big]);
    expect(draft.files.find((f) => f.path === 'logo.png')?.error).toBe(
      '不是 UTF-8 文本：技能包只能包含文本文件',
    );
    expect(draft.files.find((f) => f.path === 'data.txt')?.error).toBe('超过 1 MB');
    expect(uploadable(draft)).toBe(false);

    const many = Array.from({ length: 101 }, (_, i) => file(`refs/${String(i).padStart(3, '0')}.md`, 'x'));
    expect(readSkillPackage([file('SKILL.md', SKILL_MD), ...many]).errors).toContain(
      '至多 100 个文件，现在有 102 个',
    );
    const heavy = Array.from({ length: 6 }, (_, i) => file(`refs/${i}.txt`, 'b'.repeat(1000 * 1000)));
    expect(readSkillPackage([file('SKILL.md', SKILL_MD), ...heavy]).errors).toContain('文件合计至多 5 MB');
  });

  it('路径规则与服务端一致', () => {
    for (const ok of ['SKILL.md', 'scripts/run.py', 'a/b/c.txt']) expect(validPath(ok)).toBe(true);
    for (const bad of ['', '/etc/passwd', 'a\\b', '../x', 'a/', 'a//b']) expect(validPath(bad)).toBe(false);
    expect(stripCommonRoot(['SKILL.md', 'scripts/a.py'])).toEqual(['SKILL.md', 'scripts/a.py']);
    expect(stripCommonRoot(['x/SKILL.md', 'y/a.py'])).toEqual(['x/SKILL.md', 'y/a.py']);
  });

  it('frontmatter 前的 BOM 与没有正文', () => {
    const fm = splitFrontmatter('﻿---\nname: a\ndescription: b\n---');
    expect(fm.data).toEqual({ name: 'a', description: 'b' });
    expect(fm.body).toBe('');
  });
});
