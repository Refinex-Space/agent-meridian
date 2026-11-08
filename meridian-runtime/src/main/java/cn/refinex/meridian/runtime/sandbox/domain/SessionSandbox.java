package cn.refinex.meridian.runtime.sandbox.domain;

import cn.refinex.meridian.engine.spi.driver.SandboxHandle;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 一个会话的沙箱相关字段（{@code rt.sessions} 的沙箱列），见架构设计「会话沙箱」。
 *
 * @param sessionId 会话 ID；必填
 * @param agentVersionId 会话绑定的智能体版本，用量记录用；必填
 * @param sandbox 当前的沙箱；没有沙箱或已回收时为 null
 * @param podIp 沙箱 Pod 的 IP；没有沙箱时为 null
 * @param lastUsedAt 沙箱最近一次被轮次使用的时间；可为 null
 * @param snapshotKey 最近一次回收留下的快照键；可为 null
 * @param meteredAt vCPU 秒已计量到的时间；没有沙箱时为 null
 */
public record SessionSandbox(
        String sessionId,
        String agentVersionId,
        SandboxRecord sandbox,
        String podIp,
        Instant lastUsedAt,
        String snapshotKey,
        Instant meteredAt) {

    /**
     * 创建记录。
     *
     * @throws NullPointerException 缺少会话或智能体版本时
     */
    public SessionSandbox {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(agentVersionId, "agentVersionId");
    }

    /**
     * 当前沙箱的句柄。
     *
     * @return 句柄；没有沙箱时为空
     */
    public Optional<SandboxHandle> handle() {
        return sandbox == null || podIp == null ? Optional.empty() : Optional.of(sandbox.handle(podIp));
    }
}
