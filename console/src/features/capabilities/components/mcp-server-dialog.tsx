import { useNavigate } from '@tanstack/react-router';
import { useState } from 'react';

import { ErrorState } from '@/components/domain/error-state';
import { FormField, fieldAria } from '@/components/domain/form-field';
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
import { PlusIcon, XIcon } from '@/components/ui/glyphs';
import { Icon } from '@/components/ui/icon';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { RadioGroup, RadioGroupItem } from '@/components/ui/radio-group';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { SecretSelect } from '@/features/settings';
import { useProject } from '@/lib/access';
import { ApiError } from '@/lib/api/errors';
import type { McpServer, McpTransport, SideEffect } from '@/lib/api/types';

import { useCreateMcpServer, useUpdateMcpServer } from '../api/queries';
import {
  EMPTY_MCP_FORM,
  SIDE_EFFECT_HINT,
  SIDE_EFFECT_LABEL,
  SIDE_EFFECTS,
  toMcpForm,
  toMcpRequest,
  TRANSPORT_LABEL,
  validateMcpForm,
  type McpForm,
} from '../model/mcp';

export interface McpServerDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** 修改已登记的服务；不给时是登记新服务。 */
  server?: McpServer;
}

const DEFAULT_LEVEL = '__default__';

/** 服务端的字段错误：`name`、`url`、`header_secret_ids.<头名>` 映射到表单的行。 */
function mapServerErrors(error: unknown, form: McpForm): Record<string, string> | null {
  if (!(error instanceof ApiError)) return null;
  if (error.problem?.code === 'mcp_server.name_taken')
    return { name: '项目内已有同名的 MCP 服务，换一个名字。' };
  const violations = error.problem?.errors ?? [];
  if (violations.length === 0) return null;
  const mapped: Record<string, string> = {};
  for (const v of violations) {
    const header = /^header_secret_ids\.(.+)$/.exec(v.field);
    if (header) {
      const row = form.headers.findIndex((h) => h.name.trim() === header[1]);
      mapped[row >= 0 ? `headers.${row}` : 'headers'] = v.message;
    } else mapped[v.field === 'url' || v.field === 'name' ? v.field : 'root'] = v.message;
  }
  return mapped;
}

/**
 * 登记或修改 MCP 服务：服务名、传输、地址、注入的请求头（每个请求头取一个凭据）与默认的副作用等级。保存时服务端连接一次并
 * 列出工具，连不上就不保存，原因写在表单里；登记成功后进入服务详情，逐个标注工具的副作用等级。
 */
export function McpServerDialog({ open, onOpenChange, server }: McpServerDialogProps) {
  const { projectId } = useProject();
  const navigate = useNavigate();
  const create = useCreateMcpServer();
  const update = useUpdateMcpServer(server?.id ?? '');
  const mutation = server ? update : create;
  const [form, setForm] = useState<McpForm>(server ? toMcpForm(server) : EMPTY_MCP_FORM);
  const [submitted, setSubmitted] = useState(false);
  const [server400, setServer400] = useState<{ errors: Record<string, string>; snapshot: string } | null>(
    null,
  );

  const local = submitted ? validateMcpForm(form) : {};
  const fromServer = server400 && server400.snapshot === JSON.stringify(form) ? server400.errors : {};
  const errors: Record<string, string> = { ...fromServer, ...local };
  // 能落到字段上的错误就地显示；其余（连不上、权限、未知字段）显示在表单底部。
  const onFields = server400 !== null && !server400.errors['root'];
  const rootError = mutation.error && !onFields ? mutation.error : null;

  const reset = () => {
    setForm(server ? toMcpForm(server) : EMPTY_MCP_FORM);
    setSubmitted(false);
    setServer400(null);
    mutation.reset();
  };

  const close = (next: boolean) => {
    if (mutation.isPending) return;
    if (!next) reset();
    onOpenChange(next);
  };

  const patch = (next: Partial<McpForm>) => setForm((f) => ({ ...f, ...next }));
  const patchHeader = (i: number, next: Partial<McpForm['headers'][number]>) =>
    setForm((f) => ({ ...f, headers: f.headers.map((h, j) => (j === i ? { ...h, ...next } : h)) }));

  const submit = () => {
    setSubmitted(true);
    setServer400(null);
    if (Object.keys(validateMcpForm(form)).length > 0) return;
    // 修改时沿用已有的逐个标注，只保留仍在清单里的工具（服务端拒绝不存在的工具）。
    const kept = server
      ? Object.fromEntries(
          Object.entries(server.tool_side_effects).filter(([tool]) =>
            server.tools.some((t) => t.name === tool),
          ),
        )
      : {};
    mutation.mutate(toMcpRequest(form, kept), {
      onSuccess: (saved) => {
        close(false);
        if (!server)
          void navigate({
            to: '/projects/$projectId/capabilities/mcp-servers/$mcpServerId',
            params: { projectId, mcpServerId: saved.id },
          });
      },
      onError: (error) => {
        const mapped = mapServerErrors(error, form);
        if (mapped) setServer400({ errors: mapped, snapshot: JSON.stringify(form) });
      },
    });
  };

  return (
    <Dialog open={open} onOpenChange={close}>
      <DialogContent
        size="lg"
        onOpenAutoFocus={(e) => {
          e.preventDefault();
          document.getElementById('mcp-name')?.focus();
        }}
      >
        <DialogHeader>
          <DialogTitle>{server ? `修改 ${server.name}` : '登记 MCP 服务'}</DialogTitle>
          <DialogDescription>
            {server
              ? '保存时重新连接并列出工具；已发布的智能体版本在下一次发布时才用新配置。'
              : '保存时平台连接一次服务并列出工具，连不上就不保存。'}
          </DialogDescription>
        </DialogHeader>
        <DialogBody label="MCP 服务的配置">
          <form
            id="mcp-server-form"
            className="flex flex-col gap-4"
            onSubmit={(e) => {
              e.preventDefault();
              submit();
            }}
          >
            <FormField
              id="mcp-name"
              label="服务名"
              hint="项目内唯一，也是工具名的前缀：mcp__服务名__工具名"
              error={errors['name']}
            >
              <Input
                id="mcp-name"
                value={form.name}
                placeholder="crm"
                className="max-w-80 font-mono"
                autoComplete="off"
                onChange={(e) => patch({ name: e.target.value })}
                {...fieldAria('mcp-name', errors['name'])}
              />
            </FormField>
            <FormField id="mcp-transport" label="传输" group>
              <RadioGroup
                value={form.transport}
                onValueChange={(v) => patch({ transport: v as McpTransport })}
                aria-labelledby="mcp-transport-label"
                className="flex gap-6"
              >
                {(['streamable_http', 'sse'] as const).map((t) => (
                  <div key={t} className="flex items-center gap-2">
                    <RadioGroupItem id={`mcp-transport-${t}`} value={t} />
                    <Label htmlFor={`mcp-transport-${t}`} className="font-normal">
                      {TRANSPORT_LABEL[t]}
                    </Label>
                  </div>
                ))}
              </RadioGroup>
            </FormField>
            <FormField
              id="mcp-url"
              label="地址"
              hint="http 或 https；内网地址只在平台放行的网段内可用"
              error={errors['url']}
            >
              <Input
                id="mcp-url"
                value={form.url}
                placeholder="https://mcp.example.com/mcp"
                className="font-mono"
                autoComplete="off"
                onChange={(e) => patch({ url: e.target.value })}
                {...fieldAria('mcp-url', errors['url'])}
              />
            </FormField>
            <FormField
              id="mcp-headers"
              label="请求头"
              group
              hint="每次调用时把凭据值作为请求头的完整取值注入，例如 Authorization 对应 Bearer 加令牌；不需要认证时留空"
              error={errors['headers']}
            >
              <div role="group" aria-labelledby="mcp-headers-label" className="flex flex-col gap-2">
                {form.headers.map((header, i) => {
                  const rowError = errors[`headers.${i}`];
                  return (
                    <div key={i} className="flex flex-col gap-1">
                      <div className="flex items-center gap-2">
                        <Input
                          aria-label={`第 ${i + 1} 个请求头的名称`}
                          value={header.name}
                          placeholder="Authorization"
                          className="w-44 shrink-0 font-mono"
                          autoComplete="off"
                          onChange={(e) => patchHeader(i, { name: e.target.value })}
                          {...(rowError ? { 'aria-invalid': true } : {})}
                        />
                        <div className="min-w-0 flex-1">
                          {/* 用 label 命名而不是 aria-label：触发器显示的是凭据名，读屏名要包含可见文字（WCAG 2.5.3）。 */}
                          <Label htmlFor={`mcp-header-${i}`} className="sr-only">
                            {`${header.name || `第 ${i + 1} 个请求头`}的凭据`}
                          </Label>
                          <SecretSelect
                            id={`mcp-header-${i}`}
                            value={header.secretId}
                            onChange={(secretId) => patchHeader(i, { secretId })}
                            usedBy="project"
                            invalid={Boolean(rowError)}
                            describedBy={rowError ? `mcp-header-${i}-error` : undefined}
                          />
                        </div>
                        <Button
                          type="button"
                          size="icon-md"
                          variant="ghost"
                          aria-label={`移除第 ${i + 1} 个请求头`}
                          onClick={() => patch({ headers: form.headers.filter((_, j) => j !== i) })}
                        >
                          <Icon glyph={XIcon} />
                        </Button>
                      </div>
                      {rowError && (
                        <p id={`mcp-header-${i}-error`} className="text-12 text-danger">
                          {rowError}
                        </p>
                      )}
                    </div>
                  );
                })}
                {form.headers.length < 8 && (
                  <Button
                    type="button"
                    size="sm"
                    variant="ghost"
                    className="-ml-2 self-start"
                    icon={<Icon glyph={PlusIcon} />}
                    onClick={() =>
                      patch({
                        // 第一个请求头多半是认证：预填 Authorization。
                        headers: [
                          ...form.headers,
                          { name: form.headers.length === 0 ? 'Authorization' : '', secretId: null },
                        ],
                      })
                    }
                  >
                    添加请求头
                  </Button>
                )}
              </div>
            </FormField>
            <FormField id="mcp-side-effect" label="默认副作用等级" hint={SIDE_EFFECT_HINT}>
              <Select
                value={form.sideEffect ?? DEFAULT_LEVEL}
                onValueChange={(v) => patch({ sideEffect: v === DEFAULT_LEVEL ? null : (v as SideEffect) })}
              >
                <SelectTrigger id="mcp-side-effect" className="max-w-80">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value={DEFAULT_LEVEL}>不设置（按有副作用）</SelectItem>
                  {SIDE_EFFECTS.map((level) => (
                    <SelectItem key={level} value={level}>
                      {SIDE_EFFECT_LABEL[level]}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </FormField>
            {rootError ? (
              <ErrorState
                size="inline"
                title={
                  rootError instanceof ApiError && rootError.problem?.code === 'mcp_server.probe_failed'
                    ? '连不上这个服务，没有保存'
                    : '保存失败'
                }
                error={rootError}
              />
            ) : null}
          </form>
        </DialogBody>
        <DialogFooter>
          <Button disabled={mutation.isPending} onClick={() => close(false)}>
            取消
          </Button>
          <Button type="submit" form="mcp-server-form" variant="primary" pending={mutation.isPending}>
            {server ? '保存并重新探测' : '连接并登记'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
