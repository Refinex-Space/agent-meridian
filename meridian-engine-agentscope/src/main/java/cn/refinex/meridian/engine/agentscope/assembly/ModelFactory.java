package cn.refinex.meridian.engine.agentscope.assembly;

import cn.refinex.meridian.engine.spi.spec.ModelSpec;
import io.agentscope.core.model.Model;

/**
 * 根据模型引用返回 AgentScope {@link Model} 实例，装配时作为 {@code HarnessAgent.Builder.model(Model)} 的入参。
 *
 * <p>生产实现返回平台的 {@code RoutedModel}（T09a）：主模型与备用模型都在它内部，凭据和路由在调用时按
 * {@code PlatformContext} 解析，不经 AgentScope 的 {@code ModelRegistry} 全局注册表（架构设计「引擎装配」「模型调用」）。
 * 每次装配调用一次，每个智能体实例持有自己的模型实例。
 */
@FunctionalInterface
public interface ModelFactory {

    /**
     * 创建模型实例。
     *
     * @param model 模型引用，含主模型、备用模型与生成参数
     * @return 模型实例
     */
    Model create(ModelSpec model);
}
