/**
 * 装配的测试替身：{@code AgentAssembler} 所需的模型工厂、中间件、工具、文件系统、技能仓库的提供者，均记录被调用时的
 * 参数，供装配测试逐项断言；{@link cn.refinex.meridian.engine.agentscope.testkit.assembly.TestAssembly} 把它们组装成
 * 可直接使用的装配器。生产实现由 T04、T09a、T10a、T10b、T12a、T13 提供。
 *
 * <p>只用于测试，不进入生产构件。
 */
package cn.refinex.meridian.engine.agentscope.testkit.assembly;
