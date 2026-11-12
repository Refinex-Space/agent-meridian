/**
 * 用量账本（架构设计「计量与预算」「数据架构」，ADR-0048）：消费 Redis Stream {@code usage} 入账 {@code cp.usage_records}，按价格表版本
 * 计价，维护按月分区与保留，提供用量报表与公开接口 {@code GET /v1/usage}。
 */
package cn.refinex.meridian.controlplane.metering;
