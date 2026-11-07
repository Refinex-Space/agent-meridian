/**
 * 执行一轮之外，runtime 需要内核完成的事：沙箱操作（{@link cn.refinex.meridian.engine.spi.driver.SandboxDriver}）
 * 与直通的聊天调用（{@link cn.refinex.meridian.engine.spi.driver.ModelInvoker}），由 {@code meridian-engine-agentscope}
 * 实现，见架构设计「端口」。
 *
 * <p>签名只出现平台类型：沙箱以 {@link cn.refinex.meridian.engine.spi.driver.SandboxHandle} 与序列化后的状态
 * 表示，模型调用使用本包定义的 OpenAI 兼容消息模型。
 */
package cn.refinex.meridian.engine.spi.driver;
