package cn.refinex.meridian.controlplane.agent.app;

import cn.refinex.meridian.common.error.NotFoundException;
import cn.refinex.meridian.common.json.JsonMappers;
import cn.refinex.meridian.controlplane.agent.domain.AgentErrorCode;
import cn.refinex.meridian.controlplane.agent.domain.AgentVersion;
import cn.refinex.meridian.controlplane.agent.infra.system.AgentConfigLookupRepository;
import cn.refinex.meridian.controlplane.environment.domain.EnvironmentErrorCode;
import cn.refinex.meridian.controlplane.environment.domain.EnvironmentRevision;
import cn.refinex.meridian.controlplane.skill.app.SkillStorage;
import cn.refinex.meridian.controlplane.skill.domain.SkillErrorCode;
import cn.refinex.meridian.controlplane.skill.domain.SkillVersion;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeSet;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * 给 runtime 的配置解析（ADR-0047）：已发布的版本连同它引用的全部技能内容（runtime 不再需要其他查询就能装配引擎，凭据只以 ID 出现）、
 * 环境的当前修订或指定修订、单个技能版本的内容。按 ID 跨租户查找，结果带资源的租户与项目，由 runtime 校验归属。
 */
@Service
public class AgentConfigQuery {

    private final AgentConfigLookupRepository lookup;
    private final SkillStorage storage;

    /**
     * 创建用例。
     *
     * @param lookup 跨租户的配置查找
     * @param storage 技能文件的对象存储
     */
    public AgentConfigQuery(AgentConfigLookupRepository lookup, SkillStorage storage) {
        this.lookup = lookup;
        this.storage = storage;
    }

    /**
     * 按版本 ID 解析已发布的版本。
     *
     * @param agentVersionId 智能体版本 ID
     * @return 版本与技能内容
     * @throws NotFoundException 不存在或是草稿时（{@code agent.version_not_found}）
     */
    public Resolved resolveVersion(String agentVersionId) {
        AgentConfigLookupRepository.Published published = lookup.publishedVersion(agentVersionId)
                .orElseThrow(() ->
                        new NotFoundException(AgentErrorCode.VERSION_NOT_FOUND, "智能体版本不存在或没有发布：" + agentVersionId));
        return resolved(published);
    }

    /**
     * 按智能体 ID 解析当前发布的版本。
     *
     * @param agentId 智能体 ID
     * @return 版本与技能内容
     * @throws NotFoundException 智能体不存在、已删除或没有发布过时（{@code agent.not_found}）
     */
    public Resolved resolveCurrent(String agentId) {
        AgentConfigLookupRepository.Published published = lookup.currentVersion(agentId)
                .orElseThrow(() -> new NotFoundException(AgentErrorCode.NOT_FOUND, "智能体不存在或没有发布的版本：" + agentId));
        return resolved(published);
    }

    /**
     * 环境的当前修订或指定修订。
     *
     * @param environmentId 环境 ID
     * @param revision 修订号；为空表示当前修订
     * @return 修订
     * @throws NotFoundException 不存在时
     */
    public EnvironmentRevision environment(String environmentId, Long revision) {
        return lookup.environment(environmentId, revision)
                .orElseThrow(() -> revision == null
                        ? new NotFoundException(EnvironmentErrorCode.NOT_FOUND, "环境不存在：" + environmentId)
                        : new NotFoundException(
                                EnvironmentErrorCode.REVISION_NOT_FOUND, "环境 " + environmentId + " 没有修订 " + revision));
    }

    /**
     * 一个技能版本的内容。
     *
     * @param skillId 技能 ID
     * @param version 版本号
     * @return 内容
     * @throws NotFoundException 不存在时（{@code skill.version_not_found}）
     */
    public SkillContent skill(String skillId, int version) {
        SkillVersion found = lookup.skillVersion(skillId, version)
                .orElseThrow(() ->
                        new NotFoundException(SkillErrorCode.VERSION_NOT_FOUND, "技能版本不存在：" + skillId + " v" + version));
        return new SkillContent(found, storage.read(found));
    }

    private Resolved resolved(AgentConfigLookupRepository.Published published) {
        JsonNode spec = JsonMappers.lenient().readTree(published.version().spec());
        TreeSet<String> refs = new TreeSet<>();
        collectSkills(spec.path("skills"), refs);
        for (JsonNode subagent : spec.path("subagents")) {
            collectSkills(subagent.path("skills"), refs);
        }
        List<SkillContent> skills = new ArrayList<>();
        for (String ref : refs) {
            int separator = ref.lastIndexOf('#');
            skills.add(skill(ref.substring(0, separator), Integer.parseInt(ref.substring(separator + 1))));
        }
        return new Resolved(published.version(), published.agentDeleted(), List.copyOf(skills));
    }

    private static void collectSkills(JsonNode refs, TreeSet<String> out) {
        for (JsonNode ref : refs) {
            out.add(ref.path("skill_id").asString() + "#" + ref.path("version").asInt());
        }
    }

    /**
     * 解析结果。
     *
     * @param version 已发布的版本（规格是发布时的快照）
     * @param agentDeleted 智能体是否已删除
     * @param skills 引用的技能内容，按技能 ID 与版本排序
     */
    public record Resolved(AgentVersion version, boolean agentDeleted, List<SkillContent> skills) {}

    /**
     * 一个技能版本的内容。
     *
     * @param version 技能版本
     * @param files 相对路径 → 内容
     */
    public record SkillContent(SkillVersion version, SortedMap<String, String> files) {}
}
