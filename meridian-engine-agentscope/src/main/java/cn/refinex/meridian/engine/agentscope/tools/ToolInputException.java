package cn.refinex.meridian.engine.agentscope.tools;

/**
 * 工具调用中可预期的问题（参数不对、目标不存在、违反"先读后改"等），消息是给模型看的英文说明。{@link PlatformTool} 把它转成
 * 出错的结果交给模型，让模型修正后重试；不记错误日志。
 */
public final class ToolInputException extends RuntimeException {

    /**
     * 创建异常。
     *
     * @param message 给模型看的说明：哪里不对、怎样修正
     */
    public ToolInputException(String message) {
        super(message, null, false, false);
    }
}
