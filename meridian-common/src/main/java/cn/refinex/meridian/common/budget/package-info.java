/**
 * 四级预算的共用约定（架构设计「计量与预算」，ADR-0048）：级别、计数周期与 Redis 键。control-plane 写限额、对账，runtime 预扣与结算，
 * 两边按同一组键读写。
 */
package cn.refinex.meridian.common.budget;
