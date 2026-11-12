package cn.refinex.meridian.runtime.workspace.infra.system;

import cn.refinex.meridian.persistence.jdbc.JdbcValues;
import cn.refinex.meridian.persistence.tenant.CrossTenant;
import cn.refinex.meridian.persistence.tenant.SystemRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;

/**
 * 工作区条目 {@code rt.workspace_entries} 的仓储（架构设计「核心表」「文件系统装配」）。方法在调用方的事务中执行。
 *
 * <p>命名空间以租户与项目开头（{@code [tenant_id, project_id, end_user_id, session_id]}），每条语句都同时按
 * {@code tenant_id}、{@code project_id} 列与命名空间匹配；调用方从命名空间取这两个值，不依赖调用线程上的
 * {@code PlatformContext}——AgentScope 在自己的线程上同步调用工作区存储。
 */
@Repository
@CrossTenant(reason = "WorkspaceStorePort 端口实现：按命名空间隔离，命名空间以租户与项目开头，由平台从 PlatformContext 生成")
public class WorkspaceEntryRepository extends SystemRepository {

    /**
     * 创建仓储。
     *
     * @param dataSource 应用数据源
     */
    public WorkspaceEntryRepository(DataSource dataSource) {
        super(dataSource);
    }

    /**
     * 读取一个条目。
     *
     * @param scope 租户与项目
     * @param namespace 命名空间
     * @param key 键
     * @param lock 是否以 {@code FOR UPDATE} 锁住该行
     * @return 条目；不存在时为空
     */
    public Optional<Row> find(Scope scope, List<String> namespace, String key, boolean lock) {
        return jdbc().sql("""
                        SELECT namespace, key, version, size, inline_content::text AS inline_content, object_key
                          FROM rt.workspace_entries
                         WHERE tenant_id = :tenant_id AND project_id = :project_id AND namespace = CAST(:namespace AS text[]) AND key = :key
                        """ + (lock ? " FOR UPDATE" : ""))
                .param("tenant_id", scope.tenantId())
                .param("project_id", scope.projectId())
                .param("namespace", JdbcValues.textArray(namespace))
                .param("key", key)
                .query((rs, row) -> map(rs))
                .optional();
    }

    /**
     * 新建条目，版本号为 1；已存在时什么都不做。
     *
     * @param scope 租户与项目
     * @param namespace 命名空间
     * @param key 键
     * @param content 内容
     * @param now 当前时间
     * @return 新建了条目时为 true
     */
    public boolean insert(Scope scope, List<String> namespace, String key, Content content, Instant now) {
        return jdbc().sql("""
                                INSERT INTO rt.workspace_entries (namespace, key, tenant_id, project_id, version, size,
                                                                  content_size, inline_content, object_key, created_at,
                                                                  updated_at)
                                VALUES (CAST(:namespace AS text[]), :key, :tenant_id, :project_id, 1, :size, :content_size,
                                        :inline_content, :object_key, :now, :now)
                                ON CONFLICT (namespace, key) DO NOTHING
                                """)
                        .param("namespace", JdbcValues.textArray(namespace))
                        .param("key", key)
                        .param("tenant_id", scope.tenantId())
                        .param("project_id", scope.projectId())
                        .param("size", content.size())
                        .param("content_size", content.contentSize())
                        .param(
                                "inline_content",
                                content.inlineJson() == null ? null : JdbcValues.jsonb(content.inlineJson()))
                        .param("object_key", content.objectKey())
                        .param("now", JdbcValues.timestamptz(now))
                        .update()
                == 1;
    }

    /**
     * 更新已有条目的内容，版本号加一。
     *
     * @param scope 租户与项目
     * @param namespace 命名空间
     * @param key 键
     * @param content 内容
     * @param expectedVersion 期望的当前版本
     * @param now 当前时间
     * @return 版本匹配并更新时为 true
     */
    public boolean update(
            Scope scope, List<String> namespace, String key, Content content, long expectedVersion, Instant now) {
        return jdbc().sql("""
                                UPDATE rt.workspace_entries
                                   SET version = version + 1, size = :size, content_size = :content_size,
                                       inline_content = :inline_content, object_key = :object_key, updated_at = :now
                                 WHERE tenant_id = :tenant_id AND project_id = :project_id
                                   AND namespace = CAST(:namespace AS text[]) AND key = :key AND version = :expected_version
                                """)
                        .param("size", content.size())
                        .param("content_size", content.contentSize())
                        .param(
                                "inline_content",
                                content.inlineJson() == null ? null : JdbcValues.jsonb(content.inlineJson()))
                        .param("object_key", content.objectKey())
                        .param("now", JdbcValues.timestamptz(now))
                        .param("tenant_id", scope.tenantId())
                        .param("project_id", scope.projectId())
                        .param("namespace", JdbcValues.textArray(namespace))
                        .param("key", key)
                        .param("expected_version", expectedVersion)
                        .update()
                == 1;
    }

    /**
     * 按命名空间前缀（含子命名空间）分页读取条目，按键排序。
     *
     * @param scope 租户与项目
     * @param namespace 命名空间前缀
     * @param limit 最多返回的条数
     * @param offset 跳过的条数
     * @return 条目
     */
    public List<Row> search(Scope scope, List<String> namespace, int limit, int offset) {
        // 会话命名空间有 4 段，前 4 段的表达式命中 workspace_entries_session_idx（V0005）；切片的冒号两侧留空格，
        // 否则 Spring 会把 ":4" 当成命名参数
        String sessionPrefix =
                namespace.size() >= SESSION_SEGMENTS ? " AND namespace[1 : 4] = CAST(:session_prefix AS text[])" : "";
        var statement = jdbc().sql("""
                        SELECT namespace, key, version, size, inline_content::text AS inline_content, object_key
                          FROM rt.workspace_entries
                         WHERE tenant_id = :tenant_id AND project_id = :project_id
                           AND namespace[1 : %d] = CAST(:namespace AS text[])%s
                         ORDER BY key, namespace
                         LIMIT :limit OFFSET :offset
                        """.formatted(namespace.size(), sessionPrefix))
                .param("tenant_id", scope.tenantId())
                .param("project_id", scope.projectId())
                .param("namespace", JdbcValues.textArray(namespace))
                .param("limit", limit)
                .param("offset", offset);
        if (namespace.size() >= SESSION_SEGMENTS) {
            statement = statement.param("session_prefix", JdbcValues.textArray(namespace.subList(0, SESSION_SEGMENTS)));
        }
        return statement.query((rs, row) -> map(rs)).list();
    }

    /**
     * 列出一个会话命名空间中的文件（工作区 API，ADR-0050）：按键的字节序，游标为上一页最后的键。
     *
     * @param scope 租户与项目
     * @param namespace 会话命名空间（4 段）
     * @param keyPrefix 键的前缀（{@code /} 加相对路径前缀）；为 {@code /} 时列出全部
     * @param afterKey 上一页最后的键；为空表示第一页
     * @param limit 至多条数
     * @return 文件，按键排序
     */
    public List<Listed> list(Scope scope, List<String> namespace, String keyPrefix, String afterKey, int limit) {
        return jdbc().sql("""
                        SELECT key, content_size, updated_at
                          FROM rt.workspace_entries
                         WHERE tenant_id = :tenant_id AND project_id = :project_id
                           AND namespace[1 : 4] = CAST(:namespace AS text[])
                           AND namespace = CAST(:namespace AS text[])
                           AND starts_with(key, :prefix)
                           AND (CAST(:after AS text) IS NULL OR key COLLATE "C" > :after COLLATE "C")
                         ORDER BY key COLLATE "C"
                         LIMIT :limit
                        """)
                .param("tenant_id", scope.tenantId())
                .param("project_id", scope.projectId())
                .param("namespace", JdbcValues.textArray(namespace))
                .param("prefix", keyPrefix)
                .param("after", afterKey)
                .param("limit", limit)
                .query((rs, row) -> new Listed(
                        rs.getString("key"),
                        rs.getObject("content_size") == null ? null : rs.getLong("content_size"),
                        JdbcValues.instant(rs, "updated_at")))
                .list();
    }

    /**
     * 删除一个条目。
     *
     * @param scope 租户与项目
     * @param namespace 命名空间
     * @param key 键
     * @return 被删除条目的对象键；条目不存在或内容是内联的时为空
     */
    public Optional<String> delete(Scope scope, List<String> namespace, String key) {
        return jdbc().sql("""
                        DELETE FROM rt.workspace_entries
                         WHERE tenant_id = :tenant_id AND project_id = :project_id AND namespace = CAST(:namespace AS text[]) AND key = :key
                        RETURNING object_key
                        """)
                .param("tenant_id", scope.tenantId())
                .param("project_id", scope.projectId())
                .param("namespace", JdbcValues.textArray(namespace))
                .param("key", key)
                .query((rs, row) -> Optional.ofNullable(rs.getString("object_key")))
                .optional()
                .flatMap(objectKey -> objectKey);
    }

    /** 会话命名空间的段数：{@code [tenant_id, project_id, end_user_id, session_id]}。 */
    static final int SESSION_SEGMENTS = 4;

    private static Row map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Row(
                JdbcValues.readTextArray(rs, "namespace"),
                rs.getString("key"),
                rs.getLong("version"),
                rs.getLong("size"),
                rs.getString("inline_content"),
                rs.getString("object_key"));
    }

    /**
     * 条目所属的租户与项目，取自命名空间的前两段。
     *
     * @param tenantId 租户 ID
     * @param projectId 项目 ID
     */
    public record Scope(String tenantId, String projectId) {}

    /**
     * 要写入的内容：内联的 JSON 或对象存储中的键，二者恰好一个。
     *
     * @param size 内容大小（JSON 的字节数）
     * @param contentSize 文件内容的字节数（值是 {@code FileData} 时）；不是文件时为空
     * @param inlineJson 内联的 JSON；在对象存储时为空
     * @param objectKey 对象键；内联时为空
     */
    public record Content(long size, Long contentSize, String inlineJson, String objectKey) {}

    /**
     * 列表中的一个文件。
     *
     * @param key 键
     * @param contentSize 文件内容的字节数；没有记录时为空
     * @param updatedAt 最近一次写入的时间
     */
    public record Listed(String key, Long contentSize, Instant updatedAt) {}

    /**
     * 一行条目。
     *
     * @param namespace 命名空间
     * @param key 键
     * @param version 版本号
     * @param size 内容大小
     * @param inlineJson 内联的 JSON；在对象存储时为空
     * @param objectKey 对象键；内联时为空
     */
    public record Row(
            List<String> namespace, String key, long version, long size, String inlineJson, String objectKey) {}
}
