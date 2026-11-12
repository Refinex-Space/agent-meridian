package cn.refinex.meridian.runtime.file.infra;

import cn.refinex.meridian.persistence.jdbc.JdbcValues;
import cn.refinex.meridian.persistence.tenant.TenantScope;
import cn.refinex.meridian.persistence.tenant.TenantScopedRepository;
import cn.refinex.meridian.runtime.file.domain.StoredFile;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;

/** 上传文件的项目级仓储（{@code rt.files}，ADR-0050）：租户与项目取自 {@code PlatformContext}，其他项目的文件与不存在相同。 */
@Repository
public class FileRepository extends TenantScopedRepository {

    private static final String COLUMNS = """
            id, tenant_id, project_id, filename, media_type, size_bytes, sha256, object_key, created_by, created_at
            """;

    /**
     * 创建仓储。
     *
     * @param dataSource 应用数据源
     */
    public FileRepository(DataSource dataSource) {
        super(dataSource, TenantScope.PROJECT);
    }

    /**
     * 写入一条文件记录；租户与项目取自当前上下文，记录中的同名字段必须与之相同。
     *
     * @param file 文件
     */
    public void insert(StoredFile file) {
        sql("""
                        INSERT INTO rt.files (id, tenant_id, project_id, filename, media_type, size_bytes, sha256, object_key,
                                              created_by, created_at)
                        VALUES (:id, :tenant_id, :project_id, :filename, :media_type, :size_bytes, :sha256, :object_key,
                                :created_by, :created_at)
                        """)
                .param("id", file.id())
                .param("filename", file.filename())
                .param("media_type", file.mediaType())
                .param("size_bytes", file.sizeBytes())
                .param("sha256", file.sha256())
                .param("object_key", file.objectKey())
                .param("created_by", file.createdBy())
                .param("created_at", file.createdAt())
                .update();
    }

    /**
     * 读取一条。
     *
     * @param id 文件 ID
     * @return 文件；不存在或不属于当前项目时为空
     */
    public Optional<StoredFile> find(String id) {
        return sql("SELECT " + COLUMNS
                        + " FROM rt.files WHERE tenant_id = :tenant_id AND project_id = :project_id AND id = :id")
                .param("id", id)
                .query(FileRepository::map)
                .optional();
    }

    /**
     * 读取多条。
     *
     * @param ids 文件 ID
     * @return 存在且属于当前项目的文件，顺序不定
     */
    public List<StoredFile> findAll(Collection<String> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return sql("SELECT " + COLUMNS
                        + " FROM rt.files WHERE tenant_id = :tenant_id AND project_id = :project_id AND id = ANY(:ids)")
                .param("ids", JdbcValues.textArray(ids))
                .query(FileRepository::map)
                .list();
    }

    private static StoredFile map(ResultSet rs, int row) throws SQLException {
        return new StoredFile(
                rs.getString("id"),
                rs.getString("tenant_id"),
                rs.getString("project_id"),
                rs.getString("filename"),
                rs.getString("media_type"),
                rs.getLong("size_bytes"),
                rs.getString("sha256"),
                rs.getString("object_key"),
                rs.getString("created_by"),
                JdbcValues.instant(rs, "created_at"));
    }
}
