package cn.refinex.meridian.runtime.session.app;

import cn.refinex.meridian.engine.spi.spec.EnvironmentSpec;
import cn.refinex.meridian.engine.spi.spec.ResolvedAgentSpec;
import java.util.Set;

/**
 * 业务方声明的自定义工具不能使用的名字（架构设计「平台工具」中的保留工具名）：平台工具名、harness 仍然注册的工具名，以及智能体
 * 装配后 Toolkit 中的全部工具名；{@code mcp__} 前缀由调用方另行检查。原因是 {@code ToolRequestConfig} 遇到同名时外部工具优先，
 * 会顶掉平台工具。
 *
 * <p>实现在 {@code config} 中：内核已装配时取智能体装配后的名单，否则取平台工具名与 harness 工具名。方法会阻塞。
 */
@FunctionalInterface
public interface ReservedToolNames {

    /**
     * 返回会话绑定的智能体版本与环境下的保留工具名。
     *
     * @param agent 智能体版本配置
     * @param environment 环境配置
     * @return 保留工具名
     */
    Set<String> of(ResolvedAgentSpec agent, EnvironmentSpec environment);
}
