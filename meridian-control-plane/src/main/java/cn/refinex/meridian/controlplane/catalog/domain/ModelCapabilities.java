package cn.refinex.meridian.controlplane.catalog.domain;

/**
 * 模型能力，供控制台展示与智能体配置校验。
 *
 * @param toolCalls 支持工具调用
 * @param vision 支持图片输入
 * @param reasoning 输出推理内容
 */
public record ModelCapabilities(boolean toolCalls, boolean vision, boolean reasoning) {

    /** 都不支持。 */
    public static final ModelCapabilities NONE = new ModelCapabilities(false, false, false);
}
