package cn.refinex.meridian.controlplane.tenant.api;

import cn.refinex.meridian.api.admin.model.TenantPage;
import cn.refinex.meridian.api.admin.model.WebSearchSettings;
import cn.refinex.meridian.common.page.PageResult;
import cn.refinex.meridian.controlplane.tenant.domain.Tenant;
import cn.refinex.meridian.controlplane.tenant.domain.TenantSettings;
import cn.refinex.meridian.controlplane.web.Times;
import java.net.URI;

/** 租户的领域对象与管理 API DTO 之间的转换。 */
final class TenantMapper {

    private TenantMapper() {}

    static cn.refinex.meridian.api.admin.model.Tenant toDto(Tenant tenant) {
        return new cn.refinex.meridian.api.admin.model.Tenant(
                tenant.id(),
                tenant.name(),
                tenant.maxConcurrentTurns(),
                tenant.eventRetentionDays(),
                Times.utc(tenant.createdAt()),
                Times.utc(tenant.updatedAt()));
    }

    static TenantPage toPage(PageResult<Tenant> page) {
        return new TenantPage(
                page.data().stream().map(TenantMapper::toDto).toList(), page.hasMore(), page.nextCursor());
    }

    static cn.refinex.meridian.api.admin.model.TenantSettings toDto(TenantSettings settings) {
        TenantSettings.WebSearch web = settings.webSearch();
        return new cn.refinex.meridian.api.admin.model.TenantSettings(
                settings.systemPrompt(),
                web == null
                        ? null
                        : new WebSearchSettings(
                                WebSearchSettings.BackendEnum.fromValue(web.backend()),
                                URI.create(web.baseUrl()),
                                web.secretId()));
    }

    static TenantSettings.WebSearch toDomain(WebSearchSettings web) {
        return web == null
                ? null
                : new TenantSettings.WebSearch(
                        web.getBackend().getValue(), web.getBaseUrl().toString(), web.getSecretId());
    }
}
