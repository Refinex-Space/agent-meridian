package cn.refinex.meridian.engine.agentscope.testkit.assembly;

import cn.refinex.meridian.engine.agentscope.assembly.AgentProfile;
import cn.refinex.meridian.engine.agentscope.assembly.SkillRepositoryProvider;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import io.agentscope.core.skill.repository.RuntimeContextSkillRepository;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 返回固定技能的只读技能仓库替身，同时作为提供者（每次装配返回自身）；记录被查询时的上下文与装配对象。
 *
 * <p>线程安全：技能列表不可变，记录用写时复制列表保存。
 *
 * <p>见架构设计「测试策略」「引擎装配」中的 {@code skillRepository}。
 */
public final class StaticSkillRepository implements RuntimeContextSkillRepository, SkillRepositoryProvider {

    private final List<AgentSkill> skills;
    private final List<RuntimeContext> queriedContexts = new CopyOnWriteArrayList<>();
    private final List<AgentProfile> profiles = new CopyOnWriteArrayList<>();

    /**
     * 创建仓库。
     *
     * @param skills 技能
     */
    public StaticSkillRepository(List<AgentSkill> skills) {
        this.skills = List.copyOf(skills);
    }

    /**
     * 没有技能的仓库。
     *
     * @return 仓库
     */
    public static StaticSkillRepository empty() {
        return new StaticSkillRepository(List.of());
    }

    @Override
    public RuntimeContextSkillRepository repository(AgentProfile profile) {
        profiles.add(profile);
        return this;
    }

    @Override
    public List<AgentSkill> getAllSkills(RuntimeContext context) {
        queriedContexts.add(context);
        return skills;
    }

    @Override
    public AgentSkill getSkill(String name) {
        return skills.stream().filter(s -> s.getName().equals(name)).findFirst().orElse(null);
    }

    @Override
    public List<String> getAllSkillNames() {
        return skills.stream().map(AgentSkill::getName).toList();
    }

    @Override
    public List<AgentSkill> getAllSkills() {
        return skills;
    }

    @Override
    public boolean save(List<AgentSkill> skillsToSave, boolean force) {
        return false;
    }

    @Override
    public boolean delete(String skillName) {
        return false;
    }

    @Override
    public boolean skillExists(String skillName) {
        return getSkill(skillName) != null;
    }

    @Override
    public AgentSkillRepositoryInfo getRepositoryInfo() {
        return new AgentSkillRepositoryInfo("static", "memory", false);
    }

    @Override
    public String getSource() {
        return "static";
    }

    @Override
    public void setWriteable(boolean writeable) {
        // 只读仓库，忽略
    }

    @Override
    public boolean isWriteable() {
        return false;
    }

    /**
     * 按上下文查询技能时传入的上下文，按调用顺序。
     *
     * @return 上下文
     */
    public List<RuntimeContext> queriedContexts() {
        return List.copyOf(queriedContexts);
    }

    /**
     * 作为提供者收到的装配对象。
     *
     * @return 装配对象
     */
    public List<AgentProfile> profiles() {
        return List.copyOf(profiles);
    }
}
