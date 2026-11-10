package cn.refinex.meridian.security.oidc;

import cn.refinex.meridian.api.internal.model.ProjectAccessRequest;
import cn.refinex.meridian.common.error.CommonErrorCode;
import cn.refinex.meridian.common.error.MeridianException;
import cn.refinex.meridian.security.apikey.ApiKeyScope;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * 经 control-plane 查询项目权限的 {@link ProjectAccessResolver}（ADR-0044）：调用 {@value #RESOLVE_PATH}，结果（包括"项目不存在"）
 * 按（用户，项目）缓存 60 秒，与 API Key 的校验缓存相同；角色变更最长 60 秒后生效。
 *
 * <p>control-plane 不可用、返回非 404 的错误时抛 503，不缓存。线程安全：缓存是 Caffeine。
 */
public final class ControlPlaneProjectAccessResolver implements ProjectAccessResolver {

    /** 缓存时长。 */
    public static final Duration CACHE_TTL = Duration.ofSeconds(60);

    /** 缓存条目上限：控制台用户与项目的组合远少于它。 */
    static final long MAX_ENTRIES = 50_000;

    static final String RESOLVE_PATH = "/internal/v1/project-access:resolve";

    private final RestClient client;
    private final Cache<Key, Optional<ProjectAccess>> cache = Caffeine.newBuilder()
            .expireAfterWrite(CACHE_TTL)
            .maximumSize(MAX_ENTRIES)
            .build();

    /**
     * 创建解析器。
     *
     * @param client 指向 control-plane 的服务间调用客户端（{@code ServiceRestClients.create}）
     */
    public ControlPlaneProjectAccessResolver(RestClient client) {
        this.client = client;
    }

    @Override
    public Optional<ProjectAccess> resolve(String subject, String projectId) {
        return cache.get(new Key(subject, projectId), this::fetch);
    }

    private Optional<ProjectAccess> fetch(Key key) {
        cn.refinex.meridian.api.internal.model.ProjectAccess response;
        try {
            response = client.post()
                    .uri(RESOLVE_PATH)
                    .body(new ProjectAccessRequest(key.subject(), key.projectId()))
                    .retrieve()
                    .body(cn.refinex.meridian.api.internal.model.ProjectAccess.class);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND)) {
                return Optional.empty();
            }
            throw unavailable(e);
        } catch (RestClientException e) {
            throw unavailable(e);
        }
        if (response == null) {
            throw unavailable(new IllegalStateException("control-plane 的项目权限响应为空"));
        }
        Set<ApiKeyScope> scopes = EnumSet.noneOf(ApiKeyScope.class);
        // 不认识的作用域忽略：control-plane 先于本服务升级时，新作用域对本服务没有意义
        response.getScopes().forEach(scope -> ApiKeyScope.fromValue(scope).ifPresent(scopes::add));
        return Optional.of(new ProjectAccess(response.getTenantId(), response.getProjectId(), scopes));
    }

    private static MeridianException unavailable(Exception cause) {
        return new MeridianException(CommonErrorCode.SERVICE_UNAVAILABLE, "项目权限服务暂时不可用，请稍后重试", cause);
    }

    /**
     * 缓存键。
     *
     * @param subject 用户标识
     * @param projectId 项目 ID
     */
    private record Key(String subject, String projectId) {}
}
