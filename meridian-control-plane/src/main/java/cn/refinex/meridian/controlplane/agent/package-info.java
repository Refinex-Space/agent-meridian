/**
 * 智能体与版本（架构设计「核心概念」，ADR-0047）：智能体的管理接口、草稿版本与发布（六项校验、规格快照）、版本对比，给 runtime 的
 * 配置解析接口（已发布的版本、环境修订、技能内容），以及公开接口 {@code GET /v1/agents}。项目级资源；发布与删除经
 * {@code config.invalidate} 通知 runtime。
 */
package cn.refinex.meridian.controlplane.agent;
