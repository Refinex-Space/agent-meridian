package cn.refinex.meridian.runtime.sandbox.domain;

import cn.refinex.meridian.engine.spi.driver.SandboxHandle;
import cn.refinex.meridian.engine.spi.spec.EnvironmentSpec.SandboxSize;
import java.util.Objects;

/**
 * 会话当前的沙箱，存为 {@code rt.sessions.sandbox_state} 的 JSON 对象（ADR-0037）。
 *
 * @param sandboxId 沙箱资源名；必填
 * @param size 规格，计量 vCPU 秒用；必填
 * @param state 驱动序列化的沙箱状态（{@link SandboxHandle#state()}）；必填
 */
public record SandboxRecord(String sandboxId, SandboxSize size, String state) {

    /**
     * 创建记录。
     *
     * @throws NullPointerException 缺少字段时
     */
    public SandboxRecord {
        Objects.requireNonNull(sandboxId, "sandboxId");
        Objects.requireNonNull(size, "size");
        Objects.requireNonNull(state, "state");
    }

    /**
     * 由驱动返回的句柄与规格组成记录。
     *
     * @param handle 句柄
     * @param size 规格
     * @return 记录
     */
    public static SandboxRecord of(SandboxHandle handle, SandboxSize size) {
        return new SandboxRecord(handle.sandboxId(), size, handle.state());
    }

    /**
     * 交给驱动与内核的句柄。
     *
     * @param podIp 沙箱 Pod 的 IP
     * @return 句柄
     */
    public SandboxHandle handle(String podIp) {
        return new SandboxHandle(sandboxId, podIp, state);
    }
}
