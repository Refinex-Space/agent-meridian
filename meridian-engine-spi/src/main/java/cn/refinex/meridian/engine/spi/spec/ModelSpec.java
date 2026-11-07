package cn.refinex.meridian.engine.spi.spec;

import java.util.Objects;

/**
 * 智能体使用的模型引用与生成参数，见架构设计「模型调用」。
 *
 * <p>内核把它交给模型工厂，得到平台的 {@code RoutedModel}：主模型与备用模型都在 {@code RoutedModel}
 * 内部切换，AgentScope 的 {@code fallbackModel} 不设置（架构设计「引擎装配」）。供应商账号与凭据在调用时
 * 经 {@code ModelGateway} 按 {@code PlatformContext} 解析，这里只有模型目录中的 ID。
 *
 * @param modelId 主模型在模型目录中的 ID（{@code mdl_…}）；必填
 * @param fallbackModelId 备用模型 ID（{@code mdl_…}）；可为空，表示不降级
 * @param maxOutputTokens 单次模型调用的最大输出 token 数，取值大于 0；可为空，表示使用模型目录的默认值。
 *     预算预扣按"估算输入 token 加本值"计算
 * @param temperature 采样温度，取值 [0, 2]；可为空，表示使用模型默认值
 */
public record ModelSpec(String modelId, String fallbackModelId, Integer maxOutputTokens, Double temperature) {

    /**
     * 创建模型引用。
     *
     * @throws NullPointerException 缺少主模型 ID 时
     * @throws IllegalArgumentException 输出 token 数或温度超出取值范围时
     */
    public ModelSpec {
        Objects.requireNonNull(modelId, "modelId");
        if (maxOutputTokens != null && maxOutputTokens <= 0) {
            throw new IllegalArgumentException("maxOutputTokens 必须大于 0：" + maxOutputTokens);
        }
        if (temperature != null && (temperature < 0 || temperature > 2)) {
            throw new IllegalArgumentException("temperature 必须在 [0, 2] 内：" + temperature);
        }
    }

    /**
     * 只指定主模型、其余取默认值的引用。
     *
     * @param modelId 主模型 ID
     * @return 模型引用
     */
    public static ModelSpec of(String modelId) {
        return new ModelSpec(modelId, null, null, null);
    }
}
