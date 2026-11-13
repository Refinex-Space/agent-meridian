import { useQuery } from '@tanstack/react-query';
import { useNavigate } from '@tanstack/react-router';
import { useMemo, type ReactNode } from 'react';

import { DataTable, dataTableColumns } from '@/components/domain/data-table';
import { EmptyState } from '@/components/domain/empty-state';
import { ErrorState } from '@/components/domain/error-state';
import { FilterBar, emptyValues, useSearchFilters, type FilterDef } from '@/components/domain/filter-bar';
import { summarizeReferences } from '@/components/domain/reference-list';
import { RelativeTime } from '@/components/domain/relative-time';
import { useProject } from '@/lib/access';
import type { Skill } from '@/lib/api/types';

import { skillsQuery } from '../api/queries';

const NO_FILTERS: readonly FilterDef[] = [];
const col = dataTableColumns<Skill>();

export interface SkillTableProps {
  /** 首次使用的空态里的主操作（"上传技能"）。 */
  uploadButton: ReactNode;
}

/** 技能列表：名称、说明、最新版本、被哪些已发布的智能体版本引用、更新时间；点行进入技能详情。 */
export function SkillTable({ uploadButton }: SkillTableProps) {
  const { tenantId, projectId } = useProject();
  const navigate = useNavigate();
  const skills = useQuery(skillsQuery({ tenantId, projectId }));
  const [filters, setFilters] = useSearchFilters(NO_FILTERS);

  const q = filters.q.trim().toLowerCase();
  const rows = (skills.data ?? []).filter(
    (s) => !q || s.name.includes(q) || s.description.toLowerCase().includes(q),
  );

  const columns = useMemo(
    () =>
      col.columns([
        col.accessor('name', {
          header: '名称',
          enableHiding: false,
          sortFn: 'alphanumeric',
          meta: { width: 220 },
          cell: (c) => <span className="truncate font-mono text-12 font-medium">{c.getValue()}</span>,
        }),
        col.accessor('description', {
          header: '说明',
          enableSorting: false,
          meta: { label: '说明' },
          cell: (c) => <span className="truncate text-ink-2">{c.getValue()}</span>,
        }),
        col.accessor('latest_version', {
          header: '最新版本',
          sortFn: 'basic',
          meta: { width: 96, align: 'right' },
          cell: (c) => <span className="tabular-nums">v{c.getValue()}</span>,
        }),
        col.display({
          id: 'references',
          header: '引用',
          meta: { width: 240, label: '引用' },
          cell: (c) => {
            const refs = c.row.original.references;
            return refs.length === 0 ? (
              <span className="text-ink-2">未被引用</span>
            ) : (
              <span className="truncate">{summarizeReferences(refs)}</span>
            );
          },
        }),
        col.accessor('updated_at', {
          header: '更新',
          sortFn: 'datetime',
          meta: { width: 136 },
          cell: (c) => <RelativeTime value={c.getValue()} className="text-ink-2" />,
        }),
      ]),
    [],
  );

  return (
    <DataTable
      label="技能列表"
      columns={columns}
      data={rows}
      getRowId={(s) => s.id}
      loading={skills.isPending}
      error={
        skills.error ? (
          <ErrorState title="无法加载技能" error={skills.error} onRetry={() => void skills.refetch()} />
        ) : undefined
      }
      onRowClick={(s) =>
        void navigate({
          to: '/projects/$projectId/capabilities/skills/$skillId',
          params: { projectId, skillId: s.id },
        })
      }
      className="min-h-0 flex-1"
      empty={
        q ? (
          <EmptyState
            variant="filtered"
            title="没有匹配的技能"
            onClearFilters={() => setFilters(emptyValues(NO_FILTERS))}
          />
        ) : (
          <EmptyState
            variant="first-use"
            title="还没有技能：上传 SKILL.md 与它用到的文件，智能体按需加载"
            action={uploadButton}
          />
        )
      }
      toolbar={
        <FilterBar
          filters={NO_FILTERS}
          value={filters}
          onChange={setFilters}
          search={{ label: '搜索技能', placeholder: '名称或说明' }}
          resultCount={q ? rows.length : undefined}
        />
      }
    />
  );
}
