package cn.refinex.meridian.controlplane.apikey.app;

import cn.refinex.meridian.controlplane.apikey.domain.ApiKey;
import cn.refinex.meridian.controlplane.apikey.infra.system.ApiKeyLookupRepository;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** 按 ID 在租户中查找 API Key：请求路径不带项目时使用（预算的 API Key 级范围，ADR-0048）。 */
@Service
public class ApiKeyDirectory {

    private final ApiKeyLookupRepository repository;

    /**
     * 创建查找入口。
     *
     * @param repository 跨租户的查找仓储
     */
    public ApiKeyDirectory(ApiKeyLookupRepository repository) {
        this.repository = repository;
    }

    /**
     * 查找租户中的 Key。
     *
     * @param tenantId 租户 ID
     * @param keyId API Key ID
     * @return 元数据（含已吊销的）；不存在或不属于该租户时为空
     */
    public Optional<ApiKey> find(String tenantId, String keyId) {
        return repository.findInTenant(tenantId, keyId);
    }
}
