/**
 * 技能包在浏览器中的解析与校验：上传之前预览 frontmatter 与正文、指出不合法的文件。规则与 control-plane 的 `SkillPackage`
 * 一致（管理 API「上传技能」）：`SKILL.md` 以 `---` 包围的 YAML frontmatter 开头，`name` 与 `description` 必填；文件是
 * UTF-8 文本，路径为相对路径；至多 100 个文件，单个至多 1 MB、合计至多 5 MB。服务端仍会再校验一次。
 */
import { parse } from 'yaml';

export const SKILL_FILE = 'SKILL.md';
export const MAX_FILES = 100;
export const MAX_FILE_BYTES = 1024 * 1024;
export const MAX_TOTAL_BYTES = 5 * 1024 * 1024;
export const MAX_DESCRIPTION = 1024;
/** 技能名：与 Agent Skills 的约定一致。 */
export const SKILL_NAME = /^[a-z0-9][a-z0-9-]{0,63}$/;

/** 选中的一个文件：包内的相对路径与原始字节。 */
export interface PickedFile {
  path: string;
  bytes: Uint8Array;
}

export interface PackageFile {
  path: string;
  /** UTF-8 字节数。 */
  size: number;
  content: string;
  /** 这个文件本身的问题（不是文本、过大、路径不合法）。 */
  error?: string;
}

export interface SkillPackageDraft {
  files: PackageFile[];
  /** 忽略的隐藏文件（路径中有以 `.` 开头的一段，例如 `.DS_Store`、`.git/`）。 */
  ignored: string[];
  /** frontmatter 中的 `name`，没有或不是字符串时为空串。 */
  name: string;
  description: string;
  /** frontmatter 中 `name`、`description` 之外的键（`license`、`allowed-tools` 等），原样展示。 */
  extra: Record<string, unknown>;
  /** `SKILL.md` 中 frontmatter 之后的正文。 */
  body: string;
  /** 整个包的问题：缺少 `SKILL.md`、frontmatter 不合法、数量与大小超限、与已有技能名不一致。 */
  errors: string[];
  totalBytes: number;
}

const utf8 = new TextDecoder('utf-8', { fatal: true });

/** 路径是否合法（与服务端相同：相对路径，不含 `..`、反斜杠、空段）。 */
export function validPath(path: string): boolean {
  return (
    path.trim() !== '' &&
    !path.startsWith('/') &&
    !path.includes('\\') &&
    !path.includes('..') &&
    !path.endsWith('/') &&
    !path.includes('//')
  );
}

/** 选择文件夹时浏览器给出的路径带着文件夹名（`docx/SKILL.md`）：全部文件在同一个顶层文件夹下时去掉它。 */
export function stripCommonRoot(paths: readonly string[]): string[] {
  const first = paths[0]?.split('/')[0];
  const shared =
    first !== undefined &&
    paths.length > 0 &&
    paths.every((p) => p.includes('/') && p.split('/')[0] === first);
  return shared ? paths.map((p) => p.slice(first.length + 1)) : [...paths];
}

const hidden = (path: string) => path.split('/').some((segment) => segment.startsWith('.'));

interface Frontmatter {
  data: Record<string, unknown>;
  body: string;
  error?: string;
}

/** 解析 `SKILL.md` 开头的 frontmatter（YAML 1.1，与服务端的 SnakeYAML 一致）。 */
export function splitFrontmatter(markdown: string): Frontmatter {
  const text = markdown.startsWith('﻿') ? markdown.slice(1) : markdown;
  const end = text.indexOf('\n---', 3);
  if (!text.startsWith('---') || end < 0)
    return { data: {}, body: text, error: `${SKILL_FILE} 必须以 --- 包围的 YAML frontmatter 开头` };
  const afterClose = text.indexOf('\n', end + 4);
  const body = afterClose < 0 ? '' : text.slice(afterClose + 1).replace(/^\n+/, '');
  let parsed: unknown;
  try {
    parsed = parse(text.slice(3, end), { version: '1.1' });
  } catch {
    return { data: {}, body, error: `${SKILL_FILE} 的 frontmatter 不是合法的 YAML` };
  }
  if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed))
    return { data: {}, body, error: `${SKILL_FILE} 的 frontmatter 必须是映射` };
  return { data: parsed as Record<string, unknown>, body };
}

/**
 * 把选中的文件整理成技能包并校验。`expectedName` 给出时（上传新版本）要求 frontmatter 的 `name` 与技能名一致。
 */
export function readSkillPackage(picked: readonly PickedFile[], expectedName?: string): SkillPackageDraft {
  const ignored: string[] = [];
  const kept = picked.filter((f) => {
    if (hidden(f.path)) ignored.push(f.path);
    return !hidden(f.path);
  });
  const paths = stripCommonRoot(kept.map((f) => f.path));
  const errors: string[] = [];
  const seen = new Set<string>();
  let totalBytes = 0;
  const files: PackageFile[] = kept.map((f, i) => {
    const path = paths[i]!;
    const size = f.bytes.byteLength;
    totalBytes += size;
    let content = '';
    let error: string | undefined;
    if (!validPath(path)) error = '不是合法的相对路径';
    else if (seen.has(path)) error = '路径重复';
    else if (size > MAX_FILE_BYTES) error = '超过 1 MB';
    else {
      try {
        content = utf8.decode(f.bytes);
      } catch {
        error = '不是 UTF-8 文本：技能包只能包含文本文件';
      }
    }
    seen.add(path);
    return { path, size, content, ...(error ? { error } : {}) };
  });
  files.sort((a, b) => (a.path < b.path ? -1 : a.path > b.path ? 1 : 0));

  if (files.length > MAX_FILES) errors.push(`至多 ${MAX_FILES} 个文件，现在有 ${files.length} 个`);
  if (totalBytes > MAX_TOTAL_BYTES) errors.push('文件合计至多 5 MB');

  let name = '';
  let description = '';
  let extra: Record<string, unknown> = {};
  let body = '';
  const skill = files.find((f) => f.path === SKILL_FILE);
  if (!skill) errors.push(`技能包必须在根目录包含 ${SKILL_FILE}`);
  else if (!skill.error) {
    const fm = splitFrontmatter(skill.content);
    body = fm.body;
    if (fm.error) errors.push(fm.error);
    else {
      const { name: rawName, description: rawDescription, ...rest } = fm.data;
      name = typeof rawName === 'string' ? rawName : '';
      description = typeof rawDescription === 'string' ? rawDescription.trim() : '';
      extra = rest;
      if (!SKILL_NAME.test(name))
        errors.push(`${SKILL_FILE} 的 frontmatter 缺少合法的 name（小写字母、数字、短横线，至多 64 个字符）`);
      else if (expectedName !== undefined && name !== expectedName)
        errors.push(`新版本的 name 必须是 ${expectedName}，现在是 ${name}`);
      if (!description || description.length > MAX_DESCRIPTION)
        errors.push(`${SKILL_FILE} 的 frontmatter 缺少 description，或超过 ${MAX_DESCRIPTION} 个字符`);
    }
  }
  return { files, ignored, name, description, extra, body, errors, totalBytes };
}

/** 包能否上传：没有整体的问题，也没有出错的文件。 */
export function uploadable(draft: SkillPackageDraft): boolean {
  return draft.errors.length === 0 && draft.files.every((f) => !f.error);
}

/** 管理 API 的请求体：只含包内的文件（隐藏文件已忽略）。 */
export function toPackage(draft: SkillPackageDraft) {
  return { files: draft.files.map((f) => ({ path: f.path, content: f.content })) };
}
