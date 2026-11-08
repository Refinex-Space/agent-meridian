package cn.refinex.meridian.runtime.config;

import cn.refinex.meridian.common.concurrent.Blocking;
import cn.refinex.meridian.engine.spi.port.AgentConfigPort;
import cn.refinex.meridian.engine.spi.spec.EnvironmentSpec;
import cn.refinex.meridian.engine.spi.spec.ResolvedAgentSpec;
import cn.refinex.meridian.engine.spi.spec.SkillDefinition;
import cn.refinex.meridian.engine.spi.spec.SkillRef;
import cn.refinex.meridian.runtime.session.app.AgentConfigProvider;
import reactor.core.publisher.Mono;

/**
 * {@link AgentConfigPort} 的实现：内核在实例缓存未命中时经它读取智能体版本、环境与技能内容（ADR-0024），委托给 runtime 内部的
 * {@link AgentConfigProvider}，阻塞读取经 {@link Blocking} 在虚拟线程上执行。
 *
 * <p>线程安全：无状态。
 */
public final class ProvidedAgentConfigPort implements AgentConfigPort {

    private final AgentConfigProvider provider;

    /**
     * 创建端口实现。
     *
     * @param provider 配置来源
     */
    public ProvidedAgentConfigPort(AgentConfigProvider provider) {
        this.provider = provider;
    }

    @Override
    public Mono<ResolvedAgentSpec> agentVersion(String agentVersionId) {
        return Blocking.call(() -> provider.agentVersion(agentVersionId));
    }

    @Override
    public Mono<EnvironmentSpec> environment(String environmentId, long revision) {
        return Blocking.call(() -> provider.environment(environmentId, revision));
    }

    @Override
    public Mono<String> tenantPrompt(String tenantId) {
        return Blocking.call(() -> provider.tenantSettings(tenantId).systemPrompt());
    }

    @Override
    public Mono<SkillDefinition> skill(SkillRef ref) {
        return Blocking.call(() -> provider.skill(ref.skillId(), ref.version()));
    }
}
