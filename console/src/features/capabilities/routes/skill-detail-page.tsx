import { useQuery, useSuspenseQuery } from '@tanstack/react-query';
import { useNavigate } from '@tanstack/react-router';
import { useState, type ReactNode } from 'react';

import { CopyableId } from '@/components/domain/copyable-id';
import { DangerConfirmDialog } from '@/components/domain/danger-confirm-dialog';
import { DataRegion } from '@/components/domain/data-region';
import { KeyValueList } from '@/components/domain/key-value-list';
import { PermissionGate } from '@/components/domain/permission-gate';
import { InUseDialog, ReferenceList } from '@/components/domain/reference-list';
import { RelativeTime } from '@/components/domain/relative-time';
import { DetailPage } from '@/components/templates';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { DotsThreeIcon } from '@/components/ui/glyphs';
import { Icon } from '@/components/ui/icon';
import { Skeleton } from '@/components/ui/skeleton';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { meQuery, usePermission, useProject } from '@/lib/access';
import type { Skill, SkillVersion } from '@/lib/api/types';
import { cn } from '@/lib/cn';
import { useCrumb } from '@/lib/crumbs';
import { formatBytes, formatInstant, middleEllipsis } from '@/lib/format';

import { skillQuery, skillVersionsQuery, useDeleteSkill } from '../api/queries';
import { SkillUploadDialog } from '../components/skill-upload-dialog';

export interface SkillDetailPageProps {
  skillId: string;
}

function Section({ id, title, children }: { id: string; title: string; children: ReactNode }) {
  return (
    <section aria-labelledby={id} className="flex flex-col gap-3">
      <h2 id={id} className="text-16 font-semibold">
        {title}
      </h2>
      {children}
    </section>
  );
}

/** 版本表与选中版本的文件清单：默认选中最新版本，点行切换。 */
function Versions({ skill, me }: { skill: Skill; me: string }) {
  const { tenantId, projectId } = useProject();
  const versions = useSuspenseQuery(skillVersionsQuery({ tenantId, projectId }, skill.id)).data;
  const [selected, setSelected] = useState(skill.latest_version);
  const current: SkillVersion | undefined = versions.find((v) => v.version === selected) ?? versions.at(-1);
  const ordered = [...versions].reverse();
  return (
    <div className="flex flex-col gap-6">
      <Table aria-label="技能版本">
        <TableHeader>
          <TableRow>
            <TableHead className="w-24">版本</TableHead>
            <TableHead>说明</TableHead>
            <TableHead className="w-24 text-right">文件</TableHead>
            <TableHead className="w-28">上传人</TableHead>
            <TableHead className="w-36">上传时间</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {ordered.map((v) => (
            <TableRow key={v.version} className={cn(v.version === current?.version && 'bg-accent-weak')}>
              <TableCell>
                <button
                  type="button"
                  aria-pressed={v.version === current?.version}
                  aria-label={`查看 v${v.version} 的文件`}
                  onClick={() => setSelected(v.version)}
                  className="rounded-control font-medium text-accent tabular-nums hover:underline"
                >
                  v{v.version}
                </button>
              </TableCell>
              <TableCell>
                <span className="line-clamp-1 text-ink-2">{v.description}</span>
              </TableCell>
              <TableCell className="text-right tabular-nums">{v.files.length}</TableCell>
              <TableCell>{v.created_by === me ? '你' : middleEllipsis(v.created_by, 8, 4)}</TableCell>
              <TableCell>
                <RelativeTime value={v.created_at} className="text-ink-2" />
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
      {current && (
        <Section id="skill-files" title={`v${current.version} 的文件`}>
          <Table aria-labelledby="skill-files">
            <TableHeader>
              <TableRow>
                <TableHead>路径</TableHead>
                <TableHead className="w-24 text-right">大小</TableHead>
                <TableHead className="w-40">SHA-256</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {current.files.map((f) => (
                <TableRow key={f.path}>
                  <TableCell className="font-mono text-12">{f.path}</TableCell>
                  <TableCell className="text-right text-ink-2 tabular-nums">{formatBytes(f.size)}</TableCell>
                  <TableCell>
                    <CopyableId id={f.sha256} label={`${f.path} 的 SHA-256`} />
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </Section>
      )}
    </div>
  );
}

function Loaded({ skillId }: SkillDetailPageProps) {
  const { tenantId, projectId } = useProject();
  const navigate = useNavigate();
  const skill = useSuspenseQuery(skillQuery({ tenantId, projectId }, skillId)).data;
  const me = useQuery(meQuery()).data?.subject ?? '';
  const write = usePermission('agent_write', '上传新版本或删除技能');
  const remove = useDeleteSkill();
  const [uploading, setUploading] = useState(false);
  const [deleting, setDeleting] = useState(false);
  useCrumb(skill.name);
  const referenced = skill.references.length > 0;

  return (
    <DetailPage
      title={skill.name}
      meta={
        <>
          <Badge>最新 v{skill.latest_version}</Badge>
          <CopyableId id={skill.id} label="技能 ID" />
          <span className="text-13 text-ink-2">
            更新于 <RelativeTime value={skill.updated_at} />
          </span>
        </>
      }
      actions={
        <>
          <PermissionGate mode="disable" allowed={write.allowed} reason={write.reason}>
            <Button variant="primary" onClick={() => setUploading(true)}>
              上传新版本
            </Button>
          </PermissionGate>
          {write.allowed && (
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <Button size="icon-md" aria-label="更多操作">
                  <Icon glyph={DotsThreeIcon} />
                </Button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="end" className="min-w-40">
                <DropdownMenuItem variant="danger" onSelect={() => setDeleting(true)}>
                  删除技能
                </DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenu>
          )}
        </>
      }
    >
      <div className="flex max-w-260 flex-col gap-8 pt-6">
        <KeyValueList
          columns={2}
          items={[
            { label: '说明', value: skill.description },
            { label: '创建于', value: formatInstant(skill.created_at) },
          ]}
        />
        <Section id="skill-references" title="引用">
          {referenced ? (
            <ReferenceList
              references={skill.references}
              label="引用这个技能的智能体版本"
              className="max-w-160"
            />
          ) : (
            <p className="text-13 text-ink-2">还没有已发布的智能体版本引用它。</p>
          )}
        </Section>
        <Section id="skill-versions" title="版本">
          <DataRegion
            errorTitle="无法加载版本"
            errorSize="inline"
            skeleton={
              <div aria-hidden className="flex flex-col gap-3">
                {[0, 1, 2].map((i) => (
                  <Skeleton key={i} className="h-5 w-full" />
                ))}
              </div>
            }
          >
            <Versions skill={skill} me={me} />
          </DataRegion>
        </Section>
      </div>
      <SkillUploadDialog open={uploading} onOpenChange={setUploading} skill={skill} />
      <InUseDialog
        name={deleting && referenced ? skill.name : null}
        references={skill.references}
        nextStep="先发布不再引用它的智能体版本，或删除这些智能体，再删除这个技能。"
        onClose={() => setDeleting(false)}
      />
      <DangerConfirmDialog
        open={deleting && !referenced}
        onOpenChange={setDeleting}
        title={`删除技能 ${skill.name}`}
        confirmLabel="删除技能"
        confirmName={skill.name}
        consequences={
          <>
            <p>删除后不能再在智能体里选择它，全部版本随之删除，不能恢复。</p>
            <p>已经在运行的会话继续使用创建时的版本。</p>
          </>
        }
        onConfirm={async () => {
          await remove.mutateAsync(skill.id);
          void navigate({
            to: '/projects/$projectId/capabilities',
            params: { projectId },
            search: { tab: 'skills' },
          });
        }}
      />
    </DetailPage>
  );
}

/**
 * 技能详情（详情页模板）：说明、引用它的已发布智能体版本、版本与各版本的文件；主操作是上传新版本。被引用的技能不能删除，
 * 删除时直接列出引用方。
 */
export function SkillDetailPage({ skillId }: SkillDetailPageProps) {
  const read = usePermission('agent_read', '查看技能');
  if (!read.allowed)
    return (
      <DetailPage title="技能">
        <PermissionGate mode="explain" title="没有权限查看技能" allowed={false} reason={read.reason} />
      </DetailPage>
    );
  return (
    <DataRegion
      errorTitle="无法打开这个技能"
      skeleton={
        <DetailPage title="技能" loading>
          <div className="flex flex-col gap-3 pt-6">
            {[0, 1, 2, 3].map((i) => (
              <Skeleton key={i} className="h-5 w-full max-w-160" />
            ))}
          </div>
        </DetailPage>
      }
    >
      <Loaded skillId={skillId} />
    </DataRegion>
  );
}
