package cn.refinex.meridian.controlplane.catalog.domain;

import java.util.Arrays;
import java.util.Optional;

/** 可以创建的供应商类型（Q3，ADR-0046）：与 runtime 实现了底层客户端的类型一致。 */
public enum ProviderType {
    /** OpenAI 兼容接口：DashScope 兼容模式、vLLM、SGLang 等私有化推理服务。 */
    OPENAI_COMPATIBLE("openai_compatible"),
    /** Anthropic Messages API。 */
    ANTHROPIC("anthropic"),
    /** Gemini API。 */
    GEMINI("gemini");

    private final String value;

    ProviderType(String value) {
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
     * @return 类型；不认识时为空
     */
    public static Optional<ProviderType> fromValue(String value) {
        return Arrays.stream(values()).filter(t -> t.value.equals(value)).findFirst();
    }
}
