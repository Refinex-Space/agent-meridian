package cn.refinex.meridian.controlplane.catalog.infra.system;

import cn.refinex.meridian.controlplane.catalog.domain.CatalogModel;
import cn.refinex.meridian.controlplane.catalog.domain.ProviderAccount;
import cn.refinex.meridian.controlplane.catalog.domain.ResolvedModel;
import cn.refinex.meridian.controlplane.catalog.infra.ModelRepository;
import cn.refinex.meridian.controlplane.catalog.infra.ProviderAccountRepository;
import cn.refinex.meridian.persistence.tenant.CrossTenant;
import cn.refinex.meridian.persistence.tenant.SystemRepository;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;

/** 按模型 ID 查找模型、供应商账号与当前价格表版本：内部解析接口使用，请求不带租户。 */
@Repository
@CrossTenant(reason = "内部模型解析：runtime 按模型 ID 读取静态信息（装配时没有租户上下文），由 runtime 校验模型的租户与调用方一致（ADR-0045）")
public class CatalogLookupRepository extends SystemRepository {

    /**
     * 创建仓储。
     *
     * @param dataSource 应用的数据源
     */
    public CatalogLookupRepository(DataSource dataSource) {
        super(dataSource);
    }

    /**
     * 查找模型。
     *
     * @param modelId 模型 ID
     * @return 模型、账号与价格表当前版本；不存在时为空
     */
    public Optional<ResolvedModel> find(String modelId) {
        Optional<CatalogModel> model = jdbc().sql("SELECT * FROM cp.models WHERE id = :id")
                .param("id", modelId)
                .query(ModelRepository::map)
                .optional();
        return model.map(m -> {
            ProviderAccount account = jdbc().sql("SELECT * FROM cp.provider_accounts WHERE id = :id")
                    .param("id", m.providerAccountId())
                    .query(ProviderAccountRepository::map)
                    .single();
            Integer version = m.priceBookId() == null
                    ? null
                    : jdbc().sql("SELECT current_version FROM cp.price_books WHERE id = :id")
                            .param("id", m.priceBookId())
                            .query(Integer.class)
                            .single();
            return new ResolvedModel(m, account, version);
        });
    }
}
