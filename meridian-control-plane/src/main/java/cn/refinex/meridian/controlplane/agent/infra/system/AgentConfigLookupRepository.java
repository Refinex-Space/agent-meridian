package cn.refinex.meridian.controlplane.agent.infra.system;

import cn.refinex.meridian.controlplane.agent.domain.AgentVersion;
import cn.refinex.meridian.controlplane.agent.infra.AgentVersionRepository;
import cn.refinex.meridian.controlplane.environment.domain.EnvironmentRevision;
import cn.refinex.meridian.controlplane.environment.infra.EnvironmentRepository;
import cn.refinex.meridian.controlplane.skill.domain.SkillVersion;
import cn.refinex.meridian.controlplane.skill.infra.SkillRepository;
import cn.refinex.meridian.persistence.tenant.CrossTenant;
import cn.refinex.meridian.persistence.tenant.SystemRepository;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;

/**
 * 配置解析的跨租户查找（ADR-0047）：runtime 按版本、智能体、环境、技能 ID 解析，请求不带租户；响应带资源的租户与项目，由 runtime 校验与调用方
 * 项目一致（与模型目录的解析相同，ADR-0045）。
 */
@Repository
@CrossTenant(reason = "内部配置解析：runtime 按 ID 读取已发布的版本、环境修订与技能内容，请求不带租户，由 runtime 校验归属（ADR-0047）")
public class AgentConfigLookupRepository extends SystemRepository {

    /**
     * 创建仓储。
     *
     * @param dataSource 应用的数据源
     */
    public AgentConfigLookupRepository(DataSource dataSource) {
        super(dataSource);
    }

    /**
     * 按 ID 读取已发布的版本（智能体已删除也返回）。
     *
     * @param agentVersionId 智能体版本 ID
     * @return 版本与智能体是否已删除；不存在或是草稿时为空
     */
    public Optional<Published> publishedVersion(String agentVersionId) {
        return jdbc().sql("""
                        SELECT v.*, a.deleted_at IS NOT NULL AS agent_deleted
                          FROM cp.agent_versions v JOIN cp.agents a ON a.id = v.agent_id
                         WHERE v.id = :id AND v.status = 'published'
                        """)
                .param("id", agentVersionId)
                .query((rs, row) -> new Published(AgentVersionRepository.map(rs, row), rs.getBoolean("agent_deleted")))
                .optional();
    }

    /**
     * 未删除智能体的当前版本：版本号最大的已发布版本。
     *
     * @param agentId 智能体 ID
     * @return 版本；智能体不存在、已删除或没有发布过时为空
     */
    public Optional<Published> currentVersion(String agentId) {
        return jdbc().sql("""
                        SELECT v.*, FALSE AS agent_deleted
                          FROM cp.agent_versions v JOIN cp.agents a ON a.id = v.agent_id
                         WHERE v.agent_id = :agent_id AND v.status = 'published' AND a.deleted_at IS NULL
                         ORDER BY v.version DESC LIMIT 1
                        """)
                .param("agent_id", agentId)
                .query((rs, row) -> new Published(AgentVersionRepository.map(rs, row), false))
                .optional();
    }

    /**
     * 环境的当前修订（环境未删除），或指定修订（环境已删除也返回）。
     *
     * @param environmentId 环境 ID
     * @param revision 修订号；为空表示当前修订
     * @return 修订；不存在时为空
     */
    public Optional<EnvironmentRevision> environment(String environmentId, Long revision) {
        if (revision == null) {
            return jdbc().sql("""
                            SELECT r.* FROM cp.environments e
                              JOIN cp.environment_revisions r ON r.environment_id = e.id AND r.revision = e.revision
                             WHERE e.id = :id AND e.deleted_at IS NULL
                            """)
                    .param("id", environmentId)
                    .query(EnvironmentRepository::mapRevision)
                    .optional();
        }
        return jdbc().sql("SELECT * FROM cp.environment_revisions WHERE environment_id = :id AND revision = :revision")
                .param("id", environmentId)
                .param("revision", revision)
                .query(EnvironmentRepository::mapRevision)
                .optional();
    }

    /**
     * 一个技能版本（技能已删除也返回：已发布的版本可能仍绑定它）。
     *
     * @param skillId 技能 ID
     * @param version 版本号
     * @return 版本；不存在时为空
     */
    public Optional<SkillVersion> skillVersion(String skillId, int version) {
        return jdbc().sql("SELECT * FROM cp.skill_versions WHERE skill_id = :id AND version = :version")
                .param("id", skillId)
                .param("version", version)
                .query(SkillRepository::mapVersion)
                .optional();
    }

    /**
     * 一个已发布的版本。
     *
     * @param version 版本
     * @param agentDeleted 智能体是否已删除
     */
    public record Published(AgentVersion version, boolean agentDeleted) {}
}
