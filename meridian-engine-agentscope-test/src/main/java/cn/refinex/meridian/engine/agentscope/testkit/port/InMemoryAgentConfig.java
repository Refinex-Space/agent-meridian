package cn.refinex.meridian.engine.agentscope.testkit.port;

import cn.refinex.meridian.common.error.NotFoundException;
import cn.refinex.meridian.engine.spi.port.AgentConfigPort;
import cn.refinex.meridian.engine.spi.spec.EnvironmentSpec;
import cn.refinex.meridian.engine.spi.spec.ResolvedAgentSpec;
import cn.refinex.meridian.engine.spi.spec.SkillDefinition;
import cn.refinex.meridian.engine.spi.spec.SkillRef;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import reactor.core.publisher.Mono;

/**
 * {@link AgentConfigPort} 的内存实现：测试先登记配置，内核按 ID 读取；记录读取次数，用于断言缓存命中时不再读取。
 *
 * <p>线程安全：用并发映射保存。
 *
 * <p>见架构设计「端口」与 ADR-0024。
 */
public final class InMemoryAgentConfig implements AgentConfigPort {

    private final Map<String, ResolvedAgentSpec> agents = new ConcurrentHashMap<>();
    private final Map<String, EnvironmentSpec> environments = new ConcurrentHashMap<>();
    private final Map<String, SkillDefinition> skills = new ConcurrentHashMap<>();
    private final AtomicInteger reads = new AtomicInteger();

    /**
     * 登记智能体版本配置。
     *
     * @param spec 配置
     * @return 本对象
     */
    public InMemoryAgentConfig register(ResolvedAgentSpec spec) {
        agents.put(spec.agentVersionId(), spec);
        return this;
    }

    /**
     * 登记环境配置。
     *
     * @param spec 配置
     * @return 本对象
     */
    public InMemoryAgentConfig register(EnvironmentSpec spec) {
        environments.put(spec.environmentId() + "@" + spec.revision(), spec);
        return this;
    }

    /**
     * 登记技能内容。
     *
     * @param skill 技能内容
     * @return 本对象
     */
    public InMemoryAgentConfig register(SkillDefinition skill) {
        skills.put(skill.skillId() + "@" + skill.version(), skill);
        return this;
    }

    @Override
    public Mono<SkillDefinition> skill(SkillRef ref) {
        return Mono.defer(() -> {
            SkillDefinition skill = skills.get(ref.skillId() + "@" + ref.version());
            return skill == null
                    ? Mono.error(new NotFoundException("技能不存在：" + ref.skillId() + "@" + ref.version()))
                    : Mono.just(skill);
        });
    }

    @Override
    public Mono<ResolvedAgentSpec> agentVersion(String agentVersionId) {
        return Mono.defer(() -> {
            reads.incrementAndGet();
            ResolvedAgentSpec spec = agents.get(agentVersionId);
            return spec == null ? Mono.error(new NotFoundException("智能体版本不存在：" + agentVersionId)) : Mono.just(spec);
        });
    }

    @Override
    public Mono<EnvironmentSpec> environment(String environmentId, long revision) {
        return Mono.defer(() -> {
            reads.incrementAndGet();
            EnvironmentSpec spec = environments.get(environmentId + "@" + revision);
            return spec == null
                    ? Mono.error(new NotFoundException("环境不存在：" + environmentId + "@" + revision))
                    : Mono.just(spec);
        });
    }

    /**
     * 读取配置的总次数。
     *
     * @return 次数
     */
    public int readCount() {
        return reads.get();
    }
}
