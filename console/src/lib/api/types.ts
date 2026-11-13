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
export type Agent = AdminSchemas['Agent'];
export type AgentVersion = AdminSchemas['AgentVersion'];
export type AgentVersionComparison = AdminSchemas['AgentVersionComparison'];
export type SpecChange = AdminSchemas['SpecChange'];
export type Environment = AdminSchemas['Environment'];
export type EnvironmentRequest = AdminSchemas['EnvironmentRequest'];
export type EnvironmentRevision = AdminSchemas['EnvironmentRevision'];
export type EnvironmentTier = AdminSchemas['EnvironmentTier'];
export type SandboxSize = AdminSchemas['SandboxSize'];
export type CatalogModel = AdminSchemas['Model'];
export type Skill = AdminSchemas['Skill'];
export type SkillVersion = AdminSchemas['SkillVersion'];
export type McpServer = AdminSchemas['McpServer'];
export type FieldViolation = AdminSchemas['FieldViolation'];
export type ResourceReference = AdminSchemas['ResourceReference'];
export type SkillPackage = AdminSchemas['SkillPackage'];
export type McpServerRequest = AdminSchemas['McpServerRequest'];
export type McpTool = AdminSchemas['McpTool'];
export type McpTransport = AdminSchemas['McpTransport'];
export type SideEffect = AdminSchemas['SideEffect'];
export type ConnectivityCheck = AdminSchemas['ConnectivityCheck'];
export type Secret = AdminSchemas['Secret'];
export type ProviderAccount = AdminSchemas['ProviderAccount'];
export type ProviderType = AdminSchemas['ProviderType'];
export type RateLimit = AdminSchemas['RateLimit'];
export type CreateProviderAccountRequest = AdminSchemas['CreateProviderAccountRequest'];
export type UpdateProviderAccountRequest = AdminSchemas['UpdateProviderAccountRequest'];
export type ModelKind = AdminSchemas['ModelKind'];
export type ModelCapabilities = AdminSchemas['ModelCapabilities'];
export type CreateModelRequest = AdminSchemas['CreateModelRequest'];
export type UpdateModelRequest = AdminSchemas['UpdateModelRequest'];
export type PriceBook = AdminSchemas['PriceBook'];
export type PriceBookVersion = AdminSchemas['PriceBookVersion'];
export type TokenPrices = AdminSchemas['TokenPrices'];
export type CreatePriceBookRequest = AdminSchemas['CreatePriceBookRequest'];

/** 公开 API 中智能体页用到的别名。 */
export type KnowledgeBase = Schemas['KnowledgeBase'];
export type SessionStats = Schemas['SessionStats'];
export type AgentSessionStats = Schemas['AgentSessionStats'];

/** 公开 API 中模型测试用到的别名（模型直通接口，ADR-0056）。 */
export type ChatCompletion = Schemas['ChatCompletion'];
export type CompletionUsage = Schemas['CompletionUsage'];
export type EmbeddingList = Schemas['EmbeddingList'];
