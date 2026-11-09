/**
 * API Key 的 HTTP 处理器：管理接口（授权由 {@code @RequiresPermission} 声明）与内部校验接口（服务凭据）。只做 decode → call → map；
 * 明文只出现在创建响应中。
 */
package cn.refinex.meridian.controlplane.apikey.api;
