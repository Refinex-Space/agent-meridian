/**
 * 模型目录（架构设计「模型调用」「计量与预算」，ADR-0045、ADR-0046）：供应商账号、模型、价格表的管理接口，给 runtime 的模型解析接口。
 * 都是租户级资源；变更提交后经 {@code config.invalidate} 通知 runtime。
 */
package cn.refinex.meridian.controlplane.catalog;
