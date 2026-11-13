/**
 * 生成类型（v1.d.ts，由 `pnpm gen:api` 从 meridian-v1.yaml 生成，禁止手改）的常用别名。
 * 这里只给名字，不定义任何结构：结构一律来自 OpenAPI 规范。
 */
import type { components as AdminComponents } from './admin';
import type { components } from './v1';

export type Schemas = components['schemas'];

export type Session = Schemas['Session'];
export type SessionStatus = Schemas['SessionStatus'];
export type SessionThread = Schemas['SessionThread'];
export type SessionEvent = Schemas['SessionEvent'];
export type SessionEventType = SessionEvent['type'];
export type StreamOnlyEvent = Schemas['StreamOnlyEvent'];
export type EventPage = Schemas['EventPage'];
export type ContentBlock = Schemas['ContentBlock'];
export type TokenUsage = Schemas['TokenUsage'];
export type PendingActionSummary = Schemas['PendingActionSummary'];
export type StopReason = Schemas['StopReason'];
export type PendingAction = Schemas['PendingAction'];
export type PendingActionStatus = Schemas['PendingActionStatus'];
export type ToolResultState = Schemas['ToolResultState'];
export type PermissionOutcome = Schemas['PermissionOutcome'];
export type BlobRef = Schemas['BlobRef'];
export type Problem = Schemas['Problem'];
export type UserEventInput = Schemas['UserEventInput'];
export type SendEventsRequest = Schemas['SendEventsRequest'];
export type SendEventsResponse = Schemas['SendEventsResponse'];

/** 某一类型的事件。 */
export type EventOf<T extends SessionEventType> = Extract<SessionEvent, { type: T }>;

/** 管理 API（admin.d.ts，由 `pnpm gen:api` 从 meridian-admin-v1.yaml 生成）的常用别名。 */
export type AdminSchemas = AdminComponents['schemas'];

export type Me = AdminSchemas['Me'];
export type Membership = AdminSchemas['Membership'];
export type Role = AdminSchemas['Role'];
export type MyAccess = AdminSchemas['MyAccess'];
export type PermissionName = AdminSchemas['PermissionName'];
export type ApiKeyScope = AdminSchemas['ApiKeyScope'];
export type Project = AdminSchemas['Project'];
export type ProjectBudgetStatus = AdminSchemas['ProjectBudgetStatus'];
