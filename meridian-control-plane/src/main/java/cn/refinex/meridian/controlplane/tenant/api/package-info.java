/**
 * 租户的 HTTP 处理器：管理接口（授权由 {@code @RequiresPermission} 声明）与内部配额接口（服务凭据）。只做 decode → call → map。
 */
package cn.refinex.meridian.controlplane.tenant.api;
