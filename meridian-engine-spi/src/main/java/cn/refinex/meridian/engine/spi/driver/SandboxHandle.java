package cn.refinex.meridian.engine.spi.driver;

import java.util.Objects;

/**
 * 一个会话沙箱的句柄，runtime 只看见它与序列化后的状态，见架构设计「会话沙箱」。
 *
 * @param sandboxId 沙箱标识（集群中的沙箱资源名）；必填
 * @param podIp 沙箱 Pod 的 IP，出网代理按源 IP 鉴权时使用；必填
 * @param state 序列化后的沙箱状态（AgentScope {@code SandboxClient.serializeState} 的输出），存入
 *     {@code rt.sessions.sandbox_state}，下一轮据此恢复；必填
 */
public record SandboxHandle(String sandboxId, String podIp, String state) {

    /**
     * 创建沙箱句柄。
     *
     * @throws NullPointerException 缺少任一字段时
     */
    public SandboxHandle {
        Objects.requireNonNull(sandboxId, "sandboxId");
        Objects.requireNonNull(podIp, "podIp");
        Objects.requireNonNull(state, "state");
    }
}
