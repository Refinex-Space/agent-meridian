/**
 * 一轮的输入：{@link cn.refinex.meridian.engine.spi.engine.input.TurnInput} 的四种取值，以及它们携带的
 * 用户消息、工具确认、自定义工具结果与恢复说明。
 *
 * <p>输入必须分类，审批结果、自定义工具结果不能与普通消息放在同一轮：AgentScope 发现上下文中有待确认的
 * 工具调用时，直接进入工具执行，同一批输入里的普通文本会被丢弃（架构设计「内核适配层」）。
 *
 * <p>边界：只有平台类型，向 AgentScope {@code Msg} 的翻译在 {@code meridian-engine-agentscope}（T04）。
 */
package cn.refinex.meridian.engine.spi.engine.input;
