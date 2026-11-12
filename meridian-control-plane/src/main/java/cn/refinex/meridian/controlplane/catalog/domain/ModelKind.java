package cn.refinex.meridian.controlplane.catalog.domain;

import java.util.Arrays;
import java.util.Optional;

/** 模型的种类（ADR-0033）：决定能用于哪个接口。 */
public enum ModelKind {
    /** 对话：智能体与 {@code /v1/chat/completions}。 */
    CHAT("chat"),
    /** Embedding。 */
    EMBEDDING("embedding"),
    /** rerank。 */
    RERANK("rerank");

    private final String value;

    ModelKind(String value) {
        this.value = value;
    }

    /**
     * 返回 API 与库中的取值。
     *
     * @return 小写取值
     */
    public String value() {
        return value;
    }

    /**
     * 按取值查找。
     *
     * @param value 取值
     * @return 种类；不认识时为空
     */
    public static Optional<ModelKind> fromValue(String value) {
        return Arrays.stream(values()).filter(k -> k.value.equals(value)).findFirst();
    }
}
