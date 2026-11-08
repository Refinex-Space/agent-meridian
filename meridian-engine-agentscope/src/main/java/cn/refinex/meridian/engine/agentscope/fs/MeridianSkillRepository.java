package cn.refinex.meridian.engine.agentscope.fs;

import io.agentscope.harness.agent.skill.WorkspaceSkillRepository;

/**
 * 平台技能仓库（架构设计「引擎装配」中的 {@code skillRepository}）：技能按租户、项目、智能体解析——智能体版本引用的技能版本经
 * {@code AgentConfigPort.skill} 取得，装配时放进受保护路径 {@code skills/} 的只读视图，本仓库在这个视图上读取。
 *
 * <p>继承 2.0.4 的 {@link WorkspaceSkillRepository}（它实现 {@code RuntimeContextSkillRepository}）而不是自己实现接口，原因是
 * harness 的 {@code MarketplaceStager} 只对 {@code WorkspaceSkillRepository} 视为"工作区原生"、不暂存；其他仓库的技能资源每次
 * 构建系统提示词时都会写到本机工作区的 {@code .skills-cache/} 下，违反"会话数据不落 runtime 本地磁盘"（登记事项 D05）。
 * 技能清单从 {@code SKILL.md} 的 frontmatter 生成，{@code load_skill_through_path} 读取正文，资源按需从视图读取。
 *
 * <p>只读：{@code save}、{@code delete} 一律失败，智能体不能自建或改写技能（装配同时 {@code disableDynamicSkills()}）。
 * 线程安全：视图不可变。
 */
public final class MeridianSkillRepository extends WorkspaceSkillRepository {

    /** 技能来源的名字，出现在技能目录与日志中。 */
    public static final String SOURCE = "meridian";

    /**
     * 在技能目录的只读视图上创建仓库。视图的后端路径是 {@code /<name>/SKILL.md}，所以技能根目录为空串。
     *
     * @param skills 技能目录的只读视图（{@link ProtectedContent#skillsFilesystem()}）
     */
    public MeridianSkillRepository(ReadOnlyFilesystem skills) {
        super(skills, "", SOURCE, false);
    }

    @Override
    public void setWriteable(boolean writeable) {
        if (writeable) {
            throw new UnsupportedOperationException("平台技能仓库是只读的");
        }
    }
}
