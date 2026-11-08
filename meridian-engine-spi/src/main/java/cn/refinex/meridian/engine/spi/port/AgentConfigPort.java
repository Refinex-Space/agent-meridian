package cn.refinex.meridian.engine.spi.port;

import cn.refinex.meridian.engine.spi.spec.EnvironmentSpec;
import cn.refinex.meridian.engine.spi.spec.ResolvedAgentSpec;
import cn.refinex.meridian.engine.spi.spec.SkillDefinition;
import cn.refinex.meridian.engine.spi.spec.SkillRef;
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

    /**
     * 读取智能体版本引用的一个技能版本的内容，装配时生成受保护路径 {@code skills/} 与技能清单用（架构设计「引擎装配」
     * 中的 {@code skillRepository}）。技能版本发布后不可变，内核可以随智能体实例缓存。
     *
     * @param ref 技能引用
     * @return 技能内容；不存在时以 {@code NotFoundException} 失败
     */
    Mono<SkillDefinition> skill(SkillRef ref);
}
