package cn.refinex.meridian.knowledge.ingestion.infra.system;

import cn.refinex.meridian.persistence.tenant.CrossTenant;
import cn.refinex.meridian.persistence.tenant.SystemRepository;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;

/** 读取 {@code kb.chunks.embedding} 的实际维度，启动时与配置核对（ADR-0051）。 */
@Repository
@CrossTenant(reason = "读取表结构（pg_attribute），不涉及租户数据")
public class SchemaInspection extends SystemRepository {

    /**
     * 创建仓储。
     *
     * @param dataSource 应用的数据源
     */
    public SchemaInspection(DataSource dataSource) {
        super(dataSource);
    }

    /**
     * {@code kb.chunks.embedding} 的维度：pgvector 把维度存在列的类型修饰符中。
     *
     * @return 维度
     */
    public int embeddingDimensions() {
        return jdbc().sql("""
                        SELECT atttypmod FROM pg_attribute
                         WHERE attrelid = 'kb.chunks'::regclass AND attname = 'embedding'
                        """).query(Integer.class).single();
    }
}
