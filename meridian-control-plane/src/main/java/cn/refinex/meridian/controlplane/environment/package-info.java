/**
 * 环境（架构设计「核心概念」「环境档位」「沙箱出网」，ADR-0047）：执行档位、沙箱规格与出网白名单，修改即递增修订号并保留历史；会话固化
 * 修订号，所以 runtime 按"环境 + 修订号"读取的配置不可变。项目级资源，修改与删除经 {@code config.invalidate} 通知 runtime。
 */
package cn.refinex.meridian.controlplane.environment;
