package cn.refinex.meridian.runtime.dispatch.infra;

import cn.refinex.meridian.api.internal.model.TenantQuota;
import cn.refinex.meridian.common.error.CommonErrorCode;
import cn.refinex.meridian.common.error.MeridianException;
import cn.refinex.meridian.runtime.dispatch.app.TenantQuotaPort;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 经 control-plane 内部接口 {@code GET /internal/v1/tenants/{tenant_id}/quotas} 读取并发配额（ADR-0042）。control-plane 不可用或租户
 * 不存在时抛 503：入队失败，调用方重试；不用默认值兜底，否则配额会在下一次变更之前一直是错的。
 */
public class ControlPlaneTenantQuotas implements TenantQuotaPort {

    private final RestClient client;

    /**
     * 创建来源。
     *
     * @param client 指向 control-plane 的服务间调用客户端（{@code ServiceRestClients.create}）
     */
    public ControlPlaneTenantQuotas(RestClient client) {
        this.client = client;
    }

    @Override
    public int maxConcurrentTurns(String tenantId) {
        TenantQuota quota;
        try {
            quota = client.get()
                    .uri("/internal/v1/tenants/{tenant_id}/quotas", tenantId)
                    .retrieve()
                    .body(TenantQuota.class);
        } catch (RestClientException e) {
            throw new MeridianException(CommonErrorCode.SERVICE_UNAVAILABLE, "租户配额暂时无法读取，请稍后重试", e);
        }
        if (quota == null) {
            throw new MeridianException(CommonErrorCode.SERVICE_UNAVAILABLE, "租户配额暂时无法读取，请稍后重试");
        }
        return quota.getMaxConcurrentTurns();
    }
}
