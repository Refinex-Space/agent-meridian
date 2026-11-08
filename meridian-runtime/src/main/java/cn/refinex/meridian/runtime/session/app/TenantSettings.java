package cn.refinex.meridian.runtime.session.app;

import cn.refinex.meridian.runtime.tooling.domain.WebSearchSettings;
import java.util.Objects;
import java.util.Optional;

/**
 * runtime 用到的租户级设置（架构设计「核心表」中的 {@code cp.tenant_settings}：租户级系统提示词、联网搜索后端等）。T15b 起由
 * control-plane 下发，开发期取自 {@link AgentConfigProvider} 的文件实现。
 *
 * @param tenantId 租户 ID；必填
 * @param systemPrompt 租户层的系统提示词，装配时放在平台基础提示词之后、智能体提示词之前（ADR-0035）；没有时为空字符串
 * @param webSearch 联网搜索后端；租户没有配置时为 null，{@code web_search} 返回"未配置"的错误结果
 */
public record TenantSettings(String tenantId, String systemPrompt, WebSearchSettings webSearch) {

    /**
     * 创建租户设置。
     *
     * @throws NullPointerException 缺少租户 ID 时
     */
    public TenantSettings {
        Objects.requireNonNull(tenantId, "tenantId");
        systemPrompt = systemPrompt == null ? "" : systemPrompt;
    }

    /**
     * 没有任何设置的租户。
     *
     * @param tenantId 租户 ID
     * @return 空设置
     */
    public static TenantSettings empty(String tenantId) {
        return new TenantSettings(tenantId, "", null);
    }

    /**
     * 联网搜索后端。
     *
     * @return 配置；没有时为空
     */
    public Optional<WebSearchSettings> webSearchSettings() {
        return Optional.ofNullable(webSearch);
    }
}
