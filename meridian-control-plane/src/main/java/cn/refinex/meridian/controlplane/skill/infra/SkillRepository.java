package cn.refinex.meridian.controlplane.skill.infra;

import cn.refinex.meridian.common.json.JsonMappers;
import cn.refinex.meridian.common.page.PageRequest;
import cn.refinex.meridian.controlplane.skill.domain.Skill;
import cn.refinex.meridian.controlplane.skill.domain.SkillFile;
import cn.refinex.meridian.controlplane.skill.domain.SkillVersion;
import cn.refinex.meridian.controlplane.web.NumericCursors;
import cn.refinex.meridian.persistence.jdbc.JdbcValues;
import cn.refinex.meridian.persistence.tenant.TenantScope;
import cn.refinex.meridian.persistence.tenant.TenantScopedRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;

/** {@code cp.skills} 与 {@code cp.skill_versions} 的项目级仓储（ADR-0047）。 */
@Repository
public class SkillRepository extends TenantScopedRepository {

    /**
     * 创建仓储。
     *
     * @param dataSource 应用的数据源
     */
    public SkillRepository(DataSource dataSource) {
        super(dataSource, TenantScope.PROJECT);
    }

    /**
     * 插入技能。
     *
     * @param skill 技能，租户与项目必须等于上下文中的
     */
    public void insert(Skill skill) {
        sql("""
                        INSERT INTO cp.skills (id, tenant_id, project_id, name, description, latest_version, created_by,
                                               created_at, updated_at)
                        VALUES (:id, :tenant_id, :project_id, :name, :description, :latest, :created_by, :created_at,
                                :updated_at)
                        """)
                .param("id", skill.id())
                .param("name", skill.name())
                .param("description", skill.description())
                .param("latest", skill.latestVersion())
                .param("created_by", skill.createdBy())
                .param("created_at", skill.createdAt())
                .param("updated_at", skill.updatedAt())
                .update();
    }

    /**
     * 插入一个版本。
     *
     * @param version 版本
     */
    public void insertVersion(SkillVersion version) {
        sql("""
                        INSERT INTO cp.skill_versions (skill_id, version, tenant_id, project_id, name, description, files,
                                                       object_root, created_by, created_at)
                        VALUES (:skill_id, :version, :tenant_id, :project_id, :name, :description, :files, :object_root,
                                :created_by, :created_at)
                        """)
                .param("skill_id", version.skillId())
                .param("version", version.version())
                .param("name", version.name())
                .param("description", version.description())
                .param("files", JdbcValues.jsonbOf(version.files()))
                .param("object_root", version.objectRoot())
                .param("created_by", version.createdBy())
                .param("created_at", version.createdAt())
                .update();
    }

    /**
     * 读取未删除的技能。
     *
     * @param id 技能 ID
     * @param lock 行锁：{@code null} 不加锁，{@code "UPDATE"} 或 {@code "SHARE"}
     * @return 技能；不存在或已删除时为空
     */
    public Optional<Skill> find(String id, String lock) {
        return sql("SELECT * FROM cp.skills WHERE tenant_id = :tenant_id AND project_id = :project_id AND id = :id"
                        + " AND deleted_at IS NULL" + (lock == null ? "" : " FOR " + lock))
                .param("id", id)
                .query(SkillRepository::map)
                .optional();
    }

    /**
     * 按 ID 升序列出未删除的技能。
     *
     * @param page 分页参数
     * @return 最多 {@code page.fetchSize()} 个
     */
    public List<Skill> list(PageRequest page) {
        return sql("""
                        SELECT * FROM cp.skills
                         WHERE tenant_id = :tenant_id AND project_id = :project_id AND deleted_at IS NULL
                           AND (CAST(:after AS text) IS NULL OR id > :after)
                         ORDER BY id LIMIT :limit
                        """)
                .param("after", page.after() == null ? null : page.after().key(0))
                .param("limit", page.fetchSize())
                .query(SkillRepository::map)
                .list();
    }

    /**
     * 上传新版本后更新最新版本号与说明。
     *
     * @param id 技能 ID
     * @param latestVersion 最新版本号
     * @param description 最新版本的说明
     * @param now 上传时间
     */
    public void updateLatest(String id, int latestVersion, String description, Instant now) {
        sql("""
                        UPDATE cp.skills SET latest_version = :latest, description = :description, updated_at = :now
                         WHERE tenant_id = :tenant_id AND project_id = :project_id AND id = :id
                        """)
                .param("id", id)
                .param("latest", latestVersion)
                .param("description", description)
                .param("now", now)
                .update();
    }

    /**
     * 软删除：版本与文件保留给已绑定它们的会话。
     *
     * @param id 技能 ID
     * @param now 删除时间
     */
    public void softDelete(String id, Instant now) {
        sql("""
                        UPDATE cp.skills SET deleted_at = :now
                         WHERE tenant_id = :tenant_id AND project_id = :project_id AND id = :id AND deleted_at IS NULL
                        """).param("id", id).param("now", now).update();
    }

    /**
     * 读取一个版本（技能已删除也返回）。
     *
     * @param skillId 技能 ID
     * @param version 版本号
     * @return 版本；不存在时为空
     */
    public Optional<SkillVersion> version(String skillId, int version) {
        return sql("""
                        SELECT * FROM cp.skill_versions
                         WHERE tenant_id = :tenant_id AND project_id = :project_id AND skill_id = :skill_id
                           AND version = :version
                        """)
                .param("skill_id", skillId)
                .param("version", version)
                .query(SkillRepository::mapVersion)
                .optional();
    }

    /**
     * 按版本号升序列出版本。
     *
     * @param skillId 技能 ID
     * @param page 分页参数，游标是版本号
     * @return 最多 {@code page.fetchSize()} 个
     */
    public List<SkillVersion> versions(String skillId, PageRequest page) {
        return sql("""
                        SELECT * FROM cp.skill_versions
                         WHERE tenant_id = :tenant_id AND project_id = :project_id AND skill_id = :skill_id
                           AND (CAST(:after AS integer) IS NULL OR version > :after)
                         ORDER BY version LIMIT :limit
                        """)
                .param("skill_id", skillId)
                .param(
                        "after",
                        Optional.ofNullable(NumericCursors.after(page))
                                .map(Long::intValue)
                                .orElse(null))
                .param("limit", page.fetchSize())
                .query(SkillRepository::mapVersion)
                .list();
    }

    private static Skill map(ResultSet rs, int row) throws SQLException {
        return new Skill(
                rs.getString("id"),
                rs.getString("tenant_id"),
                rs.getString("project_id"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getInt("latest_version"),
                rs.getString("created_by"),
                JdbcValues.instant(rs, "created_at"),
                JdbcValues.instant(rs, "updated_at"));
    }

    /**
     * 把 {@code cp.skill_versions} 的一行映射为版本；跨租户的查找共用。
     *
     * @param rs 结果集
     * @param row 行号
     * @return 版本
     * @throws SQLException 读取失败时
     */
    public static SkillVersion mapVersion(ResultSet rs, int row) throws SQLException {
        return new SkillVersion(
                rs.getString("skill_id"),
                rs.getInt("version"),
                rs.getString("tenant_id"),
                rs.getString("project_id"),
                rs.getString("name"),
                rs.getString("description"),
                List.of(JsonMappers.lenient().readValue(rs.getString("files"), SkillFile[].class)),
                rs.getString("object_root"),
                rs.getString("created_by"),
                JdbcValues.instant(rs, "created_at"));
    }
}
