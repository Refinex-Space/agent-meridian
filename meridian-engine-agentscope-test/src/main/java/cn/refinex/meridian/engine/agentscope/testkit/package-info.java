/**
 * 内核测试夹具：脚本化假模型 {@code ScriptedModel}、内存端口夹具与内核场景测试，使智能体行为测试不依赖真实模型。
 *
 * <p>边界：不进入任何生产构件，只作为其他模块的 test 作用域依赖。
 *
 * <p>允许的依赖：只允许依赖 {@code meridian-engine-agentscope} 与 AgentScope。见架构设计「模块依赖规则」。
 */
package cn.refinex.meridian.engine.agentscope.testkit;
