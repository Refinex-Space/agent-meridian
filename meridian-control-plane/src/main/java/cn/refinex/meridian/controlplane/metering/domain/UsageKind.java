package cn.refinex.meridian.controlplane.metering.domain;

import java.util.Locale;

/** 计量种类（架构设计「计量与预算」的计量项，ADR-0048 第 3 条），与用量消息的 {@code kind} 一一对应。 */
public enum UsageKind {
    /** 对话与压缩的模型 token。 */
    MODEL_TOKENS,
    /** Embedding token（只有输入）。 */
    EMBEDDING_TOKENS,
    /** rerank token（只有输入）。 */
    RERANK_TOKENS,
    /** 沙箱 vCPU 秒（ADR-0037）。 */
    SANDBOX_VCPU_SECONDS,
    /** 联网搜索次数。 */
    SEARCH_CALLS;

    /**
     * 消息、数据库与接口中的取值。
     *
     * @return 小写的常量名
     */
    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * 是否为模型 token：按价格表计价，输入加输出计入预算。
     *
     * @return 模型、Embedding、rerank 时为 true
     */
    public boolean tokens() {
        return this == MODEL_TOKENS || this == EMBEDDING_TOKENS || this == RERANK_TOKENS;
    }

    /**
     * 按取值解析。
     *
     * @param value 取值
     * @return 种类
     * @throws IllegalArgumentException 取值未知时
     */
    public static UsageKind of(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
