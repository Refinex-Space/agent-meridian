package cn.refinex.meridian.controlplane.apikey.app;

import cn.refinex.meridian.common.error.NotFoundException;
import cn.refinex.meridian.controlplane.apikey.domain.ApiKey;
import cn.refinex.meridian.controlplane.apikey.domain.ApiKeyErrorCode;
import cn.refinex.meridian.controlplane.apikey.infra.system.ApiKeyLookupRepository;
import java.time.Clock;
import org.springframework.stereotype.Service;

/**
 * 内部校验接口的用例（ADR-0042）：按哈希找到有效的 Key。control-plane 是 Key 的权威来源，这里不缓存，吊销立即生效。
 */
@Service
public class ApiKeyVerifier {

    private final ApiKeyLookupRepository lookup;
    private final Clock clock;

    /**
     * 创建用例。
     *
     * @param lookup 按哈希查找的跨租户仓储
     * @param clock 时钟
     */
    public ApiKeyVerifier(ApiKeyLookupRepository lookup, Clock clock) {
        this.lookup = lookup;
        this.clock = clock;
    }

    /**
     * 校验一个哈希。
     *
     * @param keyHash 明文的 SHA-256
     * @return 有效的 Key
     * @throws NotFoundException 不存在、已吊销、已过期，或所属项目、租户已删除时（{@code api_key.not_found}）
     */
    public ApiKey verify(String keyHash) {
        return lookup.findValid(keyHash, clock.instant())
                .orElseThrow(() -> new NotFoundException(ApiKeyErrorCode.NOT_FOUND, "API Key 无效"));
    }
}
