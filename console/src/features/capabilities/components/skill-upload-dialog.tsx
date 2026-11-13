import { useNavigate } from '@tanstack/react-router';
import { useId, useRef, useState, type DragEvent } from 'react';
import { stringify } from 'yaml';

import { CodeBlock } from '@/components/domain/code-block';
import { ErrorState } from '@/components/domain/error-state';
import { KeyValueList, type KeyValueItem } from '@/components/domain/key-value-list';
import { Button } from '@/components/ui/button';
import {
  Dialog,
  DialogBody,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { WarningCircleIcon } from '@/components/ui/glyphs';
import { Icon } from '@/components/ui/icon';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { useProject } from '@/lib/access';
import { ApiError } from '@/lib/api/errors';
import type { Skill } from '@/lib/api/types';
import { cn } from '@/lib/cn';
import { formatBytes } from '@/lib/format';

import { useCreateSkill, useCreateSkillVersion } from '../api/queries';
import { readDataTransfer, readFileList } from '../model/pick-files';
import {
  readSkillPackage,
  SKILL_FILE,
  toPackage,
  uploadable,
  type PickedFile,
  type SkillPackageDraft,
} from '../model/skill-package';

export interface SkillUploadDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** 给已有技能上传新版本；不给时上传新技能（版本 1）。 */
  skill?: Skill;
}

/** 服务端的校验明细（`files`、`files.N.path`）按原文列出：浏览器已校验过同样的规则，这里只是兜底。 */
function serverMessages(error: unknown): string[] {
  if (!(error instanceof ApiError)) return [];
  if (error.problem?.code === 'skill.name_taken')
    return ['项目内已有同名的技能：在那个技能上传新版本，或改 name。'];
  return (error.problem?.errors ?? []).map((v) => v.message);
}

/** frontmatter 的值：字符串原样，标量列表用逗号连接（`allowed-tools`），其余写成单行 YAML。 */
function frontmatterValue(value: unknown): string {
  if (typeof value === 'string') return value;
  if (Array.isArray(value) && value.every((v) => typeof v !== 'object' || v === null))
    return value.join(', ');
  return stringify(value, { collectionStyle: 'flow' }).trim();
}

function FrontmatterPreview({ draft }: { draft: SkillPackageDraft }) {
  const items: KeyValueItem[] = [
    { label: 'name', value: draft.name || null, mono: true },
    { label: 'description', value: draft.description || null },
    ...Object.entries(draft.extra).map(([key, value]) => ({
      label: key,
      value: frontmatterValue(value),
      mono: typeof value !== 'string',
    })),
  ];
  return <KeyValueList items={items} />;
}

/**
 * 上传技能包：选择技能文件夹（或拖进来，或选择多个文件），先在浏览器中解析 `SKILL.md` 的 frontmatter 与正文、列出文件并
 * 按服务端的规则校验，确认无误再上传。新版本要求 frontmatter 的 name 与技能名一致。
 */
export function SkillUploadDialog({ open, onOpenChange, skill }: SkillUploadDialogProps) {
  const { projectId } = useProject();
  const navigate = useNavigate();
  const uid = useId();
  const folderInput = useRef<HTMLInputElement>(null);
  const filesInput = useRef<HTMLInputElement>(null);
  const create = useCreateSkill();
  const createVersion = useCreateSkillVersion(skill?.id ?? '');
  const mutation = skill ? createVersion : create;
  const [draft, setDraft] = useState<SkillPackageDraft | null>(null);
  const [reading, setReading] = useState(false);
  const [dragging, setDragging] = useState(false);

  const close = (next: boolean) => {
    if (mutation.isPending) return;
    if (!next) {
      setDraft(null);
      mutation.reset();
    }
    onOpenChange(next);
  };

  const load = async (read: Promise<PickedFile[]>) => {
    setReading(true);
    mutation.reset();
    try {
      setDraft(readSkillPackage(await read, skill?.name));
    } finally {
      setReading(false);
    }
  };

  const onDrop = (event: DragEvent<HTMLDivElement>) => {
    event.preventDefault();
    setDragging(false);
    void load(readDataTransfer(event.dataTransfer));
  };

  const upload = () => {
    if (!draft || !uploadable(draft)) return;
    mutation.mutate(toPackage(draft), {
      onSuccess: (result) => {
        close(false);
        if (!skill && 'id' in result)
          void navigate({
            to: '/projects/$projectId/capabilities/skills/$skillId',
            params: { projectId, skillId: result.id },
          });
      },
    });
  };

  const next = skill ? skill.latest_version + 1 : 1;
  const problems = draft ? [...draft.errors, ...serverMessages(mutation.error)] : [];
  const otherError = mutation.error && serverMessages(mutation.error).length === 0 ? mutation.error : null;

  return (
    <Dialog open={open} onOpenChange={close}>
      <DialogContent size="lg">
        <DialogHeader>
          <DialogTitle>{skill ? `上传 ${skill.name} 的 v${next}` : '上传技能'}</DialogTitle>
          <DialogDescription>
            {skill
              ? '已发布的智能体版本继续用它们引用的版本；要用新版本，在智能体里改引用后发布。'
              : `技能包是一个文件夹：根目录的 ${SKILL_FILE} 加上它用到的脚本与参考文件，全部是文本。`}
          </DialogDescription>
        </DialogHeader>
        <DialogBody label="技能包" className="flex flex-col gap-6">
          {/* 拖放只是两个选择按钮的补充，键盘与读屏用按钮完成同样的事。 */}
          {/* eslint-disable-next-line jsx-a11y/no-noninteractive-element-interactions */}
          <div
            role="region"
            aria-label="把技能文件夹拖到这里"
            onDragOver={(e) => {
              e.preventDefault();
              setDragging(true);
            }}
            onDragLeave={() => setDragging(false)}
            onDrop={onDrop}
            className={cn(
              'flex items-center gap-3 rounded-panel border border-dashed border-rule-strong px-4 py-3 transition-colors duration-150',
              dragging && 'border-accent bg-accent-weak',
            )}
          >
            <span className="flex-1 text-13 text-ink-2">
              {draft ? `已读取 ${draft.files.length} 个文件` : '把技能文件夹拖到这里，或'}
            </span>
            <Button size="sm" pending={reading} onClick={() => folderInput.current?.click()}>
              {draft ? '换一个文件夹' : '选择文件夹'}
            </Button>
            <Button size="sm" variant="ghost" disabled={reading} onClick={() => filesInput.current?.click()}>
              选择文件
            </Button>
            <input
              ref={folderInput}
              type="file"
              hidden
              aria-label="选择技能文件夹"
              // 选择文件夹：webkitdirectory 不在 React 的属性类型里，作为普通属性传入。
              {...{ webkitdirectory: '', directory: '' }}
              onChange={(e) => {
                if (e.target.files?.length) void load(readFileList(e.target.files));
                e.target.value = '';
              }}
            />
            <input
              ref={filesInput}
              type="file"
              multiple
              hidden
              aria-label="选择技能文件"
              onChange={(e) => {
                if (e.target.files?.length) void load(readFileList(e.target.files));
                e.target.value = '';
              }}
            />
          </div>

          {draft && (
            <>
              {problems.length > 0 && (
                <div
                  role="alert"
                  className="flex gap-2 rounded-panel bg-danger-weak px-4 py-3 text-13 text-danger"
                >
                  <span className="flex h-5 shrink-0 items-center">
                    <Icon glyph={WarningCircleIcon} />
                  </span>
                  <ul className="flex flex-col gap-1">
                    {problems.map((p) => (
                      <li key={p}>{p}</li>
                    ))}
                  </ul>
                </div>
              )}
              <section aria-labelledby={`${uid}-fm`} className="flex flex-col gap-3">
                <h3 id={`${uid}-fm`} className="text-13 font-semibold">
                  Frontmatter
                </h3>
                <FrontmatterPreview draft={draft} />
              </section>
              {draft.body && (
                <section aria-labelledby={`${uid}-body`} className="flex flex-col gap-3">
                  <h3 id={`${uid}-body`} className="text-13 font-semibold">
                    正文
                  </h3>
                  <CodeBlock code={draft.body} language="text" label={`${SKILL_FILE} 的正文`} wrap />
                </section>
              )}
              <section aria-labelledby={`${uid}-files`} className="flex flex-col gap-3">
                <h3 id={`${uid}-files`} className="text-13 font-semibold">
                  文件
                  <span className="ml-2 font-normal text-ink-2 tabular-nums">
                    {draft.files.length} 个，{formatBytes(draft.totalBytes)}
                  </span>
                </h3>
                <Table aria-labelledby={`${uid}-files`}>
                  <TableHeader>
                    <TableRow>
                      <TableHead>路径</TableHead>
                      <TableHead className="w-24 text-right">大小</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {draft.files.map((f) => (
                      <TableRow key={f.path}>
                        <TableCell>
                          <span className="font-mono text-12">{f.path}</span>
                          {f.error && <span className="ml-3 text-12 text-danger">{f.error}</span>}
                        </TableCell>
                        <TableCell className="text-right text-ink-2 tabular-nums">
                          {formatBytes(f.size)}
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
                {draft.ignored.length > 0 && (
                  <p className="text-12 text-ink-2">已忽略 {draft.ignored.length} 个隐藏文件</p>
                )}
              </section>
            </>
          )}
          {otherError ? <ErrorState size="inline" title="上传失败" error={otherError} /> : null}
        </DialogBody>
        <DialogFooter>
          <Button disabled={mutation.isPending} onClick={() => close(false)}>
            取消
          </Button>
          <Button
            variant="primary"
            disabled={!draft || !uploadable(draft)}
            pending={mutation.isPending}
            onClick={upload}
          >
            {skill ? `上传 v${next}` : '上传技能'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
