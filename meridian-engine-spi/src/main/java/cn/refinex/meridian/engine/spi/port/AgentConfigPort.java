package cn.refinex.meridian.engine.spi.port;

import cn.refinex.meridian.engine.spi.spec.EnvironmentSpec;
import cn.refinex.meridian.engine.spi.spec.ResolvedAgentSpec;
import reactor.core.publisher.Mono;

/**
 * 读取已解析的智能体版本与环境配置，内核在智能体实例缓存未命中时调用，由 runtime 的 {@code config} 实现
 * （配置缓存，经 control-plane 内部接口获取，{@code config.invalidate} 时失效），见架构设计「端口」「引擎装配」。
 *
 * <p>{@code TurnContext} 只带配置的 ID 与修订号，配置本身经本端口按需读取。智能体版本与指定修订号的环境都不可变，
 * 所以结果可以长期缓存。
 */
public interface AgentConfigPort {

    /**
     * 读取智能体版本配置。
     *
     * @param agentVersionId 智能体版本 ID（{@code agv_…}）
     * @return 已解析的配置；不存在时以 {@code NotFoundException} 失败
     */
    Mono<ResolvedAgentSpec> agentVersion(String agentVersionId);

    /**
     * 读取环境在指定修订号下的配置。
     *
     * @param environmentId 环境 ID（{@code env_…}）
     * @param revision 修订号，从 1 开始
     * @return 已解析的配置；不存在时以 {@code NotFoundException} 失败
     */
    Mono<EnvironmentSpec> environment(String environmentId, long revision);
}
