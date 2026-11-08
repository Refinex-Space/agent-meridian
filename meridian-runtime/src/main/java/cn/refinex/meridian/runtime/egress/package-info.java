/**
 * 出网上下文：沙箱出网代理的授权（架构设计「沙箱出网」）。代理带上源 Pod IP 与目标调用 runtime，runtime 按
 * {@code rt.sessions.sandbox_pod_ip} 找到会话与环境，按环境的白名单判定；按源 IP 解析出的授权缓存 30 秒，Pod IP 变化时立即失效。
 */
package cn.refinex.meridian.runtime.egress;
