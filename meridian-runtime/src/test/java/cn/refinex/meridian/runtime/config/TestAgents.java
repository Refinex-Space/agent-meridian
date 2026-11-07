package cn.refinex.meridian.runtime.config;

import cn.refinex.meridian.common.error.NotFoundException;
import cn.refinex.meridian.engine.spi.spec.EnvironmentSpec;
import cn.refinex.meridian.engine.spi.spec.ResolvedAgentSpec;
import cn.refinex.meridian.runtime.session.app.AgentConfigProvider;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 内存中的智能体配置来源：测试登记智能体版本与环境，多个节点共用一份。 */
final class TestAgents implements AgentConfigProvider {

    private final Map<String, ResolvedAgentSpec> agents = new ConcurrentHashMap<>();
    private final Map<String, EnvironmentSpec> environments = new ConcurrentHashMap<>();

    TestAgents register(ResolvedAgentSpec spec) {
        agents.put(spec.agentVersionId(), spec);
        return this;
    }

    TestAgents register(EnvironmentSpec environment) {
        environments.put(environment.environmentId() + "@" + environment.revision(), environment);
        return this;
    }

    @Override
    public ResolvedAgentSpec agentVersion(String agentVersionId) {
        ResolvedAgentSpec spec = agents.get(agentVersionId);
        if (spec == null) {
            throw new NotFoundException("没有智能体版本：" + agentVersionId);
        }
        return spec;
    }

    @Override
    public EnvironmentSpec environment(String environmentId, long revision) {
        EnvironmentSpec environment = environments.get(environmentId + "@" + revision);
        if (environment == null) {
            throw new NotFoundException("没有环境：" + environmentId);
        }
        return environment;
    }

    @Override
    public ResolvedAgentSpec currentAgentVersion(String agentId) {
        return agents.values().stream()
                .filter(spec -> spec.agentId().equals(agentId))
                .max(Comparator.comparing(ResolvedAgentSpec::agentVersionId))
                .orElseThrow(() -> new NotFoundException("没有智能体：" + agentId));
    }

    @Override
    public EnvironmentSpec currentEnvironment(String environmentId) {
        return environments.values().stream()
                .filter(environment -> environment.environmentId().equals(environmentId))
                .max(Comparator.comparingLong(EnvironmentSpec::revision))
                .orElseThrow(() -> new NotFoundException("没有环境：" + environmentId));
    }
}
