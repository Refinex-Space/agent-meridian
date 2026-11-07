/**
 * 脚本化假模型 {@link cn.refinex.meridian.engine.agentscope.testkit.model.ScriptedModel}：按脚本依次返回文本、思考块、
 * 工具调用、结构化输出、错误与用量，并记录每次收到的请求，使智能体行为测试不依赖真实模型（架构原则第 10 条）。
 *
 * <p>只用于测试，不进入生产构件。
 */
package cn.refinex.meridian.engine.agentscope.testkit.model;
