package cn.refinex.meridian.runtime.approval.infra;

import cn.refinex.meridian.api.internal.model.InternalPermissionRule;
import cn.refinex.meridian.api.internal.model.ProjectPermissionRules;
import cn.refinex.meridian.api.internal.model.RememberRuleRequest;
import cn.refinex.meridian.common.context.PlatformContext;
import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.engine.spi.spec.PermissionBehavior;
import cn.refinex.meridian.engine.spi.spec.PermissionRule;
import cn.refinex.meridian.engine.spi.spec.PolicyLayer;
import cn.refinex.meridian.runtime.approval.app.PolicyStorePort;
import cn.refinex.meridian.runtime.session.domain.ConfigUnavailableException;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * {@link PolicyStorePort} 的生产实现：租户与项目策略由 control-plane 管理（ADR-0047），每轮合成权限时按项目读取两层规则并缓存
 * {@code ttl}（默认 60 秒）；收到 {@code config.invalidate} 的 {@code permission_policy}（{@code id} 为项目或租户）立即淘汰
 * （{@link #evict}）。"记住此决定"写入 control-plane 后淘汰本节点的缓存，其他节点经通知淘汰。
 *
 * <p>租户与项目取自当前线程绑定的 {@code PlatformContext}。control-plane 读不到时抛可重试的 {@link ConfigUnavailableException}：轮次被
 * 置回队列，审批回应以 503 失败，调用方可以重试。
 *
 * <p>线程安全：Caffeine 按键计算；方法会阻塞（HTTP）。
 */
public final class ControlPlanePolicyStore implements PolicyStorePort {

    private final RestClient client;
    private final Cache<String, Layers> cache;

    /**
     * 创建实现。
     *
     * @param client 调 control-plane 内部接口的客户端（带服务 JWT）
     * @param ttl 缓存时间
     */
    public ControlPlanePolicyStore(RestClient client, Duration ttl) {
        this.client = client;
        this.cache =
                Caffeine.newBuilder().expireAfterWrite(ttl).maximumSize(10_000).build();
    }

    @Override
    public List<PermissionRule> tenantRules() {
        return layers().tenantRules();
    }

    @Override
    public List<PermissionRule> projectRules() {
        return layers().projectRules();
    }

    @Override
    public void rememberProjectRule(PermissionRule rule, String actionId, String decidedBy) {
        String projectId = PlatformContexts.current().projectId();
        try {
            client.post()
                    .uri("/internal/v1/projects/{id}/permission-rules", projectId)
                    .body(new RememberRuleRequest()
                            .toolName(rule.toolName())
                            .ruleContent(rule.ruleContent())
                            .behavior(RememberRuleRequest.BehaviorEnum.fromValue(
                                    rule.behavior().value()))
                            .sourceActionId(actionId)
                            .decidedBy(decidedBy))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            throw new ConfigUnavailableException("记住此决定暂时无法写入，请稍后重试", e);
        }
        cache.invalidate(projectId);
    }

    /**
     * 策略变更：{@code id} 是项目时淘汰该项目，是租户时淘汰该租户的全部项目。
     *
     * @param id 项目 ID 或租户 ID
     */
    public void evict(String id) {
        cache.invalidate(id);
        cache.asMap().values().removeIf(layers -> layers.tenantId().equals(id));
    }

    private Layers layers() {
        PlatformContext platform = PlatformContexts.current();
        Layers layers = cache.get(platform.projectId(), this::fetch);
        if (!layers.tenantId().equals(platform.tenantId())) {
            throw new IllegalStateException("项目 " + platform.projectId() + " 不属于租户 " + platform.tenantId());
        }
        return layers;
    }

    private Layers fetch(String projectId) {
        ProjectPermissionRules dto;
        try {
            dto = client.get()
                    .uri("/internal/v1/projects/{id}/permission-rules", projectId)
                    .retrieve()
                    .body(ProjectPermissionRules.class);
        } catch (RestClientException e) {
            throw new ConfigUnavailableException("权限策略暂时无法读取，请稍后重试", e);
        }
        if (dto == null) {
            throw new ConfigUnavailableException("权限策略暂时无法读取，请稍后重试", null);
        }
        return new Layers(
                dto.getTenantId(),
                dto.getTenantRules().stream()
                        .map(r -> toRule(r, PolicyLayer.TENANT))
                        .toList(),
                dto.getProjectRules().stream()
                        .map(r -> toRule(r, PolicyLayer.PROJECT))
                        .toList());
    }

    private static PermissionRule toRule(InternalPermissionRule rule, PolicyLayer layer) {
        return new PermissionRule(
                rule.getToolName(),
                rule.getRuleContent(),
                PermissionBehavior.valueOf(rule.getBehavior().getValue().toUpperCase(Locale.ROOT)),
                layer);
    }

    /**
     * 一个项目的两层规则。
     *
     * @param tenantId 项目所属租户
     * @param tenantRules 租户策略
     * @param projectRules 项目策略
     */
    private record Layers(String tenantId, List<PermissionRule> tenantRules, List<PermissionRule> projectRules) {}
}
