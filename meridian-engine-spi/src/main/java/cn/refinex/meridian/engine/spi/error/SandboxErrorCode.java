package cn.refinex.meridian.engine.spi.error;

import cn.refinex.meridian.common.error.ErrorCode;

/** 沙箱驱动的错误码，见架构设计「会话沙箱」。 */
public enum SandboxErrorCode implements ErrorCode {
    /** 认领、连接或操作沙箱失败（预热池耗尽后冷启动超时、集群或 sandbox-router 不可用等），可重试。 */
    SANDBOX_UNAVAILABLE("sandbox.unavailable", 503, "沙箱暂时不可用");

    private final String code;
    private final int status;
    private final String title;

    SandboxErrorCode(String code, int status, String title) {
        this.code = code;
        this.status = status;
        this.title = title;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public int status() {
        return status;
    }

    @Override
    public String title() {
        return title;
    }
}
