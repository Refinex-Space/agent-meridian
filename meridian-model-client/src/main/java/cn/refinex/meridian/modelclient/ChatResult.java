package cn.refinex.meridian.modelclient;

/**
 * 对话补全的结果。
 *
 * @param content 模型输出的文本，不含思考内容；没有输出时为空串
 * @param promptTokens 输入 token；只用于观测，记账在 runtime
 * @param completionTokens 输出 token；只用于观测
 */
public record ChatResult(String content, long promptTokens, long completionTokens) {}
