/**
 * 内核适配层的入口：{@link cn.refinex.meridian.engine.spi.engine.AgentEngine} 与一轮的上下文
 * {@link cn.refinex.meridian.engine.spi.engine.TurnContext}，见架构设计「内核适配层」。
 *
 * <p>平台掌控会话语义，内核只执行一轮：runtime 的 Worker 认领轮次、准备上下文与输入后调用
 * {@code runTurn}，把输出的事件持久化。内核需要的 runtime 能力经 {@code port} 包的端口获取。
 */
package cn.refinex.meridian.engine.spi.engine;
