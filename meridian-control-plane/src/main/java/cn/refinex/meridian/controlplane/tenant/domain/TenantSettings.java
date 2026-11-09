package cn.refinex.meridian.controlplane.tenant.domain;

import java.time.Instant;

/**
 * 租户设置：系统提示词的租户层与联网搜索后端（ADR-0035）。T15b 起下发到 runtime。
 *
 * @param systemPrompt 租户层系统提示词；空字符串表示没有
 * @param webSearch 联网搜索后端；可为空，表示未配置
 * @param updatedAt 最近一次修改时间；从未设置过时为空
 */
public record TenantSettings(String systemPrompt, WebSearch webSearch, Instant updatedAt) {

    /**
     * 没有设置过的租户。
     *
     * @return 默认设置
     */
    public static TenantSettings defaults() {
        return new TenantSettings("", null, null);
    }

    /**
     * 联网搜索后端。
     *
     * @param backend 后端类型：{@code searxng} 或 {@code bocha}
     * @param baseUrl 基础 URL
     * @param secretId 访问凭据的 ID（{@code sec_…}）；可为空，表示不需要凭据
     */
    public record WebSearch(String backend, String baseUrl, String secretId) {}
}
