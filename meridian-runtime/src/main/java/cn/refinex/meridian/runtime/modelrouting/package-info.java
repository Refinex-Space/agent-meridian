/**
 * 模型路由上下文：{@code ModelGateway} 端口的实现，供内核的 {@code RoutedModel} 在每次模型调用中解析供应商账号与凭据、限流、
 * 预算预扣与结算、写用量记录，见架构设计「端口」「模型调用」「计量与预算」。
 *
 * <p>供应商、模型目录与预算限额经内部端口读取（{@code app.ModelCatalogPort}、{@code app.BudgetLimitPort}），T09a 的开发实现读取
 * {@code dev-config/providers}、{@code dev-config/budgets}；T15a、T16 起由 control-plane 下发。限流与预算计数在 Redis 中用 Lua
 * 原子更新；用量记录经 outbox 的 {@code usage} 主题投递给 control-plane。
 */
package cn.refinex.meridian.runtime.modelrouting;
