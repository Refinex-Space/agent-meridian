package cn.refinex.meridian.runtime.sandbox.infra;

import cn.refinex.meridian.persistence.jdbc.JdbcValues;
import cn.refinex.meridian.persistence.tenant.TenantScope;
import cn.refinex.meridian.persistence.tenant.TenantScopedRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;

/** {@code rt.sandbox_outputs}（项目级）：产物目录 {@code outputs/} 同步到对象存储的清单。 */
@Repository
public class SandboxOutputRepository extends TenantScopedRepository {

    /**
     * 创建仓储。
     *
     * @param dataSource 应用数据源
     */
    public SandboxOutputRepository(DataSource dataSource) {
        super(dataSource, TenantScope.PROJECT);
    }

    /**
     * 上次同步的清单。
     *
     * @param sessionId 会话 ID
     * @return 路径 → SHA-256，按路径排列
     */
    public Map<String, String> synced(String sessionId) {
        Map<String, String> synced = new LinkedHashMap<>();
        sql("""
                        SELECT path, sha256 FROM rt.sandbox_outputs
                         WHERE session_id = :id AND tenant_id = :tenant_id AND project_id = :project_id
                         ORDER BY path
                        """)
                .param("id", sessionId)
                .query((rs, row) -> synced.put(rs.getString("path"), rs.getString("sha256")))
                .list();
        return synced;
    }

    /**
     * 记录一个文件已同步。
     *
     * @param sessionId 会话 ID
     * @param path 相对 {@code outputs/} 的路径
     * @param size 字节数
     * @param sha256 内容摘要
     * @param objectKey 对象键
     * @param now 当前时间
     */
    public void upsert(String sessionId, String path, long size, String sha256, String objectKey, Instant now) {
        sql("""
                        INSERT INTO rt.sandbox_outputs (session_id, path, tenant_id, project_id, size, sha256, object_key,
                                                        synced_at)
                        VALUES (:id, :path, :tenant_id, :project_id, :size, :sha256, :object_key, :now)
                        ON CONFLICT (session_id, path)
                        DO UPDATE SET size = EXCLUDED.size, sha256 = EXCLUDED.sha256, object_key = EXCLUDED.object_key,
                                      synced_at = EXCLUDED.synced_at
                        """)
                .param("id", sessionId)
                .param("path", path)
                .param("size", size)
                .param("sha256", sha256)
                .param("object_key", objectKey)
                .param("now", JdbcValues.timestamptz(now))
                .update();
    }

    /**
     * 删除一个文件的同步记录。
     *
     * @param sessionId 会话 ID
     * @param path 相对 {@code outputs/} 的路径
     */
    public void delete(String sessionId, String path) {
        sql("""
                        DELETE FROM rt.sandbox_outputs
                         WHERE session_id = :id AND path = :path AND tenant_id = :tenant_id AND project_id = :project_id
                        """).param("id", sessionId).param("path", path).update();
    }

    /**
     * 删除会话的全部同步记录。
     *
     * @param sessionId 会话 ID
     */
    public void deleteAll(String sessionId) {
        sql("""
                        DELETE FROM rt.sandbox_outputs
                         WHERE session_id = :id AND tenant_id = :tenant_id AND project_id = :project_id
                        """).param("id", sessionId).update();
    }
}
