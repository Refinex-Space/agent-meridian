/**
 * 事件表的跨租户仓储：租约条件、seq 分配、事件插入与读取。租户与项目取自会话行，不取 {@code PlatformContext}，
 * 因为回收器等后台作业没有请求上下文（架构设计「租户隔离」）。只能被 {@code stream.app} 使用。
 */
package cn.refinex.meridian.runtime.stream.infra.system;
