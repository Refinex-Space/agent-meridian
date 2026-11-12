/**
 * 模型路由上下文：{@code ModelGateway} 端口的实现，供内核的 {@code RoutedModel} 在每次模型调用中解析供应商账号与凭据、限流、
 * 预算预扣与结算、写用量记录，见架构设计「端口」「模型调用」「计量与预算」。
 *
 * <p>供应商与模型目录经 {@code app.ModelCatalogPort} 读取：生产是 control-plane 的内部接口（{@code infra.ControlPlaneModelCatalog}，
 * 缓存加 {@code config.invalidate} 淘汰，ADR-0045），开发与测试可用 {@code dev-config/providers}；模型必须属于调用方租户。预算限额经
 * {@code app.BudgetLimitPort} 读开发配置，T16 起由 control-plane 下发。限流与预算计数在 Redis 中用 Lua 原子更新；用量记录（带价格表
 * 版本）经 outbox 的 {@code usage} 主题投递给 control-plane。
 */
package cn.refinex.meridian.runtime.modelrouting;
