/**
 * 租户与项目策略（架构设计「权限判定」中的第 2、3 层，ADR-0047）：管理员添加的规则与审批时"记住此决定"写入的项目级规则，runtime 每轮合成
 * 权限时经内部接口读取（缓存，{@code config.invalidate} 立即淘汰）。
 */
package cn.refinex.meridian.controlplane.policy;
