import { useCallback, useMemo, useState, type FocusEvent } from 'react';
import { useForm, type UseFormReturn } from 'react-hook-form';

import { ApiError } from '@/lib/api/errors';
import type { Agent, AgentVersion } from '@/lib/api/types';

import { useSaveDraft, useUpdateAgent } from '../../api/mutations';
import {
  anchorOf,
  fieldId,
  groupOf,
  ROOT_ANCHOR,
  sortAnchors,
  splitField,
  type AgentGroupId,
} from '../../model/fields';
import { AGENT_NAME, specSchema, toDraftSpec, type DraftSpec, type SpecContext } from '../../model/spec';

/** 编辑页的表单值：智能体的名字与说明（PATCH 智能体）加草稿规格（PUT 版本）。 */
export interface AgentForm {
  name: string;
  description: string;
  spec: DraftSpec;
}

/** 锚点 → 错误说明。 */
export type FieldErrors = Readonly<Record<string, string>>;

/** 按路径取值（`spec.subagents.0.tools`）。 */
function valueAt(values: unknown, anchor: string): unknown {
  return anchor
    .split('.')
    .reduce<unknown>((v, key) => (v as Record<string, unknown> | undefined)?.[key], values);
}

/** 本地校验：名字与说明的规则同 `CreateAgentRequest`，规格按 `specSchema`（JSON Schema 加发布规则）。每个锚点只留第一条。 */
export function validateAgentForm(values: AgentForm, context: SpecContext): FieldErrors {
  const errors: Record<string, string> = {};
  const add = (anchor: string, message: string) => {
    errors[anchor] ??= message;
  };
  const name = values.name.trim();
  if (!name) add('name', '写一个名字，项目内不能重复。');
  else if (!AGENT_NAME.test(name))
    add('name', '小写字母开头，只用小写字母、数字、下划线与短横线，至多 64 个字符。');
  if (values.description.length > 2000)
    add('description', `说明至多 2,000 个字符，现在是 ${values.description.length} 个。`);
  const result = specSchema(context).safeParse(values.spec);
  if (!result.success)
    for (const issue of result.error.issues)
      add(
        anchorOf(['spec', ...issue.path.map((p) => (typeof p === 'symbol' ? String(p) : p))]),
        issue.message,
      );
  return errors;
}

/** 让第一个出错的字段进入视野并获得焦点（控件组聚焦组内第一个可聚焦的控件）。 */
export function focusField(anchor: string): void {
  const el = document.getElementById(fieldId(anchor));
  if (!el) return;
  el.scrollIntoView({ block: 'center' });
  const target = el.matches('input,textarea,button,[tabindex]')
    ? el
    : el.querySelector<HTMLElement>(
        'input,textarea,button,[role="combobox"],[tabindex]:not([tabindex="-1"])',
      );
  (target ?? el).focus({ preventScroll: true });
}

interface ServerError {
  message: string;
  /** 出错时这个字段的值；用户改了它之后错误不再显示。 */
  snapshot: string;
}

export interface AgentEditor {
  form: UseFormReturn<AgentForm>;
  /** 当前要显示的错误（本地校验在提交后或字段失焦后显示；服务端错误在字段值改变前一直显示）。 */
  errors: FieldErrors;
  invalidGroups: ReadonlySet<AgentGroupId>;
  /** 没有对应字段的服务端错误（显示在保存栏上方）。 */
  rootError: unknown;
  dirty: boolean;
  saving: boolean;
  savedAt: string;
  /** 保存草稿：全量校验，有错误时聚焦第一个；成功返回 true。 */
  save: () => Promise<boolean>;
  /** 只校验（没有改动时发布前用）：有错误时聚焦第一个，返回是否通过。 */
  check: () => boolean;
  discard: () => void;
  /** 把服务端的校验错误（发布失败）放到字段上并聚焦第一个。 */
  applyServerError: (error: unknown) => void;
  /** 挂在表单容器上：字段失焦后显示它的格式错误。 */
  onBlurCapture: (event: FocusEvent<HTMLElement>) => void;
}

/** 名字冲突没有字段明细，按错误码放到名字上。 */
const CODE_FIELDS: Record<string, { anchor: string; message: string }> = {
  'agent.name_taken': { anchor: 'name', message: '项目内已有同名的智能体，换一个名字。' },
};

/**
 * 智能体编辑页的状态：React Hook Form 管值与是否有改动，校验与错误定位由本 Hook 统一处理——本地 Zod 的路径与服务端
 * `errors[].field` 都经 `anchorOf` 映射到显示错误的字段（T22-A02）。
 */
export function useAgentEditor(agent: Agent, version: AgentVersion, context: SpecContext): AgentEditor {
  const initial = useMemo<AgentForm>(
    () => ({ name: agent.name, description: agent.description, spec: toDraftSpec(version.spec) }),
    [agent.name, agent.description, version.spec],
  );
  const form = useForm<AgentForm>({ defaultValues: initial });
  // 每次输入都要重算校验与错误：订阅整个表单。本项目不启用 React Compiler，这条规则只提示编译器会跳过本组件。
  // eslint-disable-next-line react-hooks/incompatible-library
  const values = form.watch();
  const [submitted, setSubmitted] = useState(false);
  const [touched, setTouched] = useState<ReadonlySet<string>>(new Set());
  const [server, setServer] = useState<Readonly<Record<string, ServerError>>>({});
  const [rootError, setRootError] = useState<unknown>(null);
  const [savedAt, setSavedAt] = useState(version.updated_at);
  const updateAgent = useUpdateAgent(agent.id);
  const saveDraft = useSaveDraft(agent.id, version.version);

  const local = validateAgentForm(values, context);
  const errors: Record<string, string> = {};
  for (const [anchor, error] of Object.entries(server))
    if (JSON.stringify(valueAt(values, anchor) ?? null) === error.snapshot) errors[anchor] = error.message;
  for (const [anchor, message] of Object.entries(local))
    if (submitted || touched.has(anchor)) errors[anchor] = message;
  const invalidGroups = new Set(
    Object.keys(errors)
      .map(groupOf)
      .filter((g) => g !== null),
  );

  const applyServerError = useCallback(
    (error: unknown) => {
      const current = form.getValues();
      const mapped: Record<string, ServerError> = {};
      const violations = error instanceof ApiError ? (error.problem?.errors ?? []) : [];
      const byCode = error instanceof ApiError ? CODE_FIELDS[error.problem?.code ?? ''] : undefined;
      const entries = byCode
        ? [{ anchor: byCode.anchor, message: byCode.message }]
        : violations.map((v) => ({ anchor: anchorOf(splitField(v.field)), message: v.message }));
      for (const { anchor, message } of entries) {
        if (anchor === ROOT_ANCHOR || mapped[anchor]) continue;
        mapped[anchor] = { message, snapshot: JSON.stringify(valueAt(current, anchor) ?? null) };
      }
      setServer(mapped);
      const anchors = sortAnchors(Object.keys(mapped));
      // 有字段明细的错误就地显示；没有的（冲突、版本已发布、服务不可用）显示在保存栏上方。
      setRootError(anchors.length > 0 && entries.every((e) => e.anchor !== ROOT_ANCHOR) ? null : error);
      if (anchors[0]) window.requestAnimationFrame(() => focusField(anchors[0]!));
    },
    [form],
  );

  const check = useCallback(() => {
    setSubmitted(true);
    const first = sortAnchors(Object.keys(validateAgentForm(form.getValues(), context)))[0];
    if (first) window.requestAnimationFrame(() => focusField(first));
    return first === undefined;
  }, [context, form]);

  const save = useCallback(async () => {
    const current = form.getValues();
    if (!check()) return false;
    setRootError(null);
    setServer({});
    try {
      const name = current.name.trim();
      if (name !== initial.name || current.description !== initial.description)
        await updateAgent.mutateAsync({ name, description: current.description });
      const saved = await saveDraft.mutateAsync(current.spec);
      setSavedAt(saved.updated_at);
      form.reset({ ...current, name });
      return true;
    } catch (error) {
      applyServerError(error);
      return false;
    }
  }, [applyServerError, check, form, initial, saveDraft, updateAgent]);

  const discard = useCallback(() => {
    form.reset();
    setSubmitted(false);
    setTouched(new Set());
    setServer({});
    setRootError(null);
  }, [form]);

  const onBlurCapture = useCallback((event: FocusEvent<HTMLElement>) => {
    const { id } = event.target;
    // 控件 id 是 field- 加锚点（点号换成短横线）；字段名只有 snake_case 与数字，换回来没有歧义。
    if (!id.startsWith('field-')) return;
    const anchor = id.slice('field-'.length).replaceAll('-', '.');
    setTouched((prev) => (prev.has(anchor) ? prev : new Set(prev).add(anchor)));
  }, []);

  return {
    form,
    errors,
    invalidGroups,
    rootError,
    dirty: form.formState.isDirty,
    saving: updateAgent.isPending || saveDraft.isPending,
    savedAt,
    save,
    check,
    discard,
    applyServerError,
    onBlurCapture,
  };
}
