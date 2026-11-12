package cn.refinex.meridian.runtime.retention.infra;

import cn.refinex.meridian.api.internal.model.TenantQuota;
import cn.refinex.meridian.runtime.retention.app.EventRetentionPolicyPort;
import cn.refinex.meridian.runtime.session.domain.ConfigUnavailableException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** 经 control-plane 内部接口 {@code GET /internal/v1/tenants/{tenant_id}/quotas} 读取事件保留天数（ADR-0049）。 */
public class ControlPlaneEventRetention implements EventRetentionPolicyPort {

    private final RestClient client;

    /**
     * 创建来源。
     *
     * @param client 指向 control-plane 的服务间调用客户端
     */
    public ControlPlaneEventRetention(RestClient client) {
        this.client = client;
    }

    @Override
    public int eventRetentionDays(String tenantId) {
        TenantQuota quota;
        try {
            quota = client.get()
                    .uri("/internal/v1/tenants/{tenant_id}/quotas", tenantId)
                    .retrieve()
                    .body(TenantQuota.class);
        } catch (RestClientException e) {
            throw new ConfigUnavailableException("租户的事件保留天数暂时无法读取：" + tenantId, e);
        }
        if (quota == null) {
            throw new ConfigUnavailableException("租户的事件保留天数暂时无法读取：" + tenantId, null);
        }
        return quota.getEventRetentionDays();
    }
}
