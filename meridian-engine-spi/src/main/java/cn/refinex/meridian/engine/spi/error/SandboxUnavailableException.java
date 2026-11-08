package cn.refinex.meridian.engine.spi.error;

/**
 * {@code SandboxDriver} 的操作因沙箱基础设施失败：认领超时、连不上沙箱、快照读写失败等。
 *
 * <p>属于可重试的错误：在轮次中发生时本轮置回队列，下次认领时重新获取沙箱。沙箱已不存在（{@code resume}）不用它，
 * 而是 {@code NotFoundException}，调用方据此改为从快照恢复。
 */
public class SandboxUnavailableException extends RetryableTurnException {

    /**
     * 创建异常。
     *
     * @param detail 面向调用方的说明，不含集群地址与凭据
     * @param cause 底层错误，只进日志
     */
    public SandboxUnavailableException(String detail, Throwable cause) {
        super(SandboxErrorCode.SANDBOX_UNAVAILABLE, detail, cause);
    }
}
