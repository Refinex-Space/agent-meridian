package cn.refinex.meridian.runtime.modelrouting.domain;

import java.util.Locale;

/** 模型目录中模型的种类：决定它能被哪个接口调用（对话、Embedding、rerank）。 */
public enum ModelKind {
    /** 对话模型：智能体与 {@code /v1/chat/completions}。 */
    CHAT,
    /** 向量模型：{@code /v1/embeddings} 与 {@code /internal/v1/embeddings}。 */
    EMBEDDING,
    /** 重排模型：{@code /internal/v1/rerank}。 */
    RERANK;

    /**
     * 配置中的取值。
     *
     * @return 小写的常量名，例如 {@code embedding}
     */
    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * 按配置中的取值查找。
     *
     * @param value 取值；为空时表示对话模型
     * @return 种类
     * @throws IllegalArgumentException 取值未知时
     */
    public static ModelKind of(String value) {
        return value == null ? CHAT : valueOf(value.toUpperCase(Locale.ROOT));
    }
}
