package cn.refinex.meridian.engine.agentscope.assembly;

import io.agentscope.core.skill.repository.RuntimeContextSkillRepository;

/**
 * 提供平台技能仓库，装配时作为 {@code HarnessAgent.Builder.skillRepository(...)} 的入参（架构设计「引擎装配」）。
 *
 * <p>生产实现为 T10a 的 {@code MeridianSkillRepository}。装配器同时调用 {@code disableDynamicSkills()}，AgentScope
 * 会在构建时用 {@code RuntimeContext.empty()} 枚举一次技能并冻结清单，所以实现必须按 {@link AgentProfile#skills()}
 * 解析，不能依赖调用时的上下文；技能资源的读取仍按调用时的上下文进行。
 */
@FunctionalInterface
public interface SkillRepositoryProvider {

    /**
     * 返回本次装配使用的技能仓库。
     *
     * @param profile 装配对象
     * @return 技能仓库
     */
    RuntimeContextSkillRepository repository(AgentProfile profile);
}
