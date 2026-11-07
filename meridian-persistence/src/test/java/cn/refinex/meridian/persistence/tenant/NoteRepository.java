package cn.refinex.meridian.persistence.tenant;

import cn.refinex.meridian.persistence.jdbc.JdbcValues;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;

/** 测试用的项目级仓储。 */
public class NoteRepository extends TenantScopedRepository {

    public NoteRepository(DataSource dataSource) {
        super(dataSource, TenantScope.PROJECT);
    }

    public record Note(String id, String body, List<String> tags, Instant createdAt) {}

    public void insert(String id, String body, List<String> tags, Instant createdAt) {
        sql("""
                INSERT INTO rt.t02_notes (tenant_id, project_id, id, body, tags, attributes, created_at)
                VALUES (:tenant_id, :project_id, :id, :body, :tags, :attributes, :created_at)
                """)
                .param("id", id)
                .param("body", body)
                .param("tags", JdbcValues.textArray(tags))
                .param("attributes", JdbcValues.jsonbOf(java.util.Map.of("source_id", id)))
                .param("created_at", createdAt)
                .update();
    }

    public Optional<Note> find(String id) {
        return sql("""
                SELECT id, body, tags, created_at FROM rt.t02_notes
                WHERE tenant_id = :tenant_id AND project_id = :project_id AND id = :id
                """)
                .param("id", id)
                .query((rs, row) -> new Note(
                        rs.getString("id"),
                        rs.getString("body"),
                        JdbcValues.readTextArray(rs, "tags"),
                        JdbcValues.instant(rs, "created_at")))
                .optional();
    }

    public List<String> listIds() {
        return sql("SELECT id FROM rt.t02_notes WHERE tenant_id = :tenant_id AND project_id = :project_id ORDER BY id")
                .query(String.class)
                .list();
    }

    public int updateBody(String id, String body) {
        return sql("UPDATE rt.t02_notes SET body = :body WHERE tenant_id = :tenant_id AND project_id = :project_id AND id = :id")
                .param("id", id)
                .param("body", body)
                .update();
    }

    public int delete(String id) {
        return sql("DELETE FROM rt.t02_notes WHERE tenant_id = :tenant_id AND project_id = :project_id AND id = :id")
                .param("id", id)
                .update();
    }

    public String attributeSource(String id) {
        return sql("""
                SELECT attributes ->> 'source_id' FROM rt.t02_notes
                WHERE tenant_id = :tenant_id AND project_id = :project_id AND id = :id
                """).param("id", id).query(String.class).single();
    }
}
