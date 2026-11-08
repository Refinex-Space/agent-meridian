package cn.refinex.meridian.runtime.sandbox.domain;

import cn.refinex.meridian.engine.spi.spec.EnvironmentSpec.SandboxSize;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * 沙箱 vCPU 秒的计量（架构设计「计量与预算」的计量项）：沙箱从认领到删除的存活时间乘以规格的 vCPU 数，按"已计量到的时间"分段记录
 * （每轮结束、空闲回收、删除会话时各记一段），长期活跃的会话不必等到沙箱删除才有用量。
 */
public final class SandboxMetering {

    /** vCPU 秒保留的小数位：毫秒级。 */
    static final int SCALE = 3;

    private SandboxMetering() {}

    /**
     * 规格的 vCPU 数（架构设计「沙箱 Pod 与镜像」的规格表）。
     *
     * @param size 规格
     * @return vCPU 数
     */
    public static int vcpu(SandboxSize size) {
        return switch (size) {
            case SMALL -> 1;
            case MEDIUM -> 2;
            case LARGE -> 4;
        };
    }

    /**
     * 一段时间的 vCPU 秒。
     *
     * @param size 规格
     * @param from 起点（已计量到的时间）
     * @param to 终点
     * @return vCPU 秒；时间段为空或倒置时为空
     */
    public static Optional<BigDecimal> vcpuSeconds(SandboxSize size, Instant from, Instant to) {
        if (from == null || !to.isAfter(from)) {
            return Optional.empty();
        }
        BigDecimal seconds = BigDecimal.valueOf(Duration.between(from, to).toMillis())
                .divide(BigDecimal.valueOf(1000), SCALE, RoundingMode.HALF_UP);
        return Optional.of(seconds.multiply(BigDecimal.valueOf(vcpu(size))));
    }
}
