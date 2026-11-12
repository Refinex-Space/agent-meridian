package cn.refinex.meridian.runtime.session.infra;

import cn.refinex.meridian.api.internal.model.InternalTenantSettings;
import cn.refinex.meridian.api.internal.model.InternalWebSearchSettings;
import cn.refinex.meridian.api.internal.model.ResolvedAgentVersion;
import cn.refinex.meridian.api.internal.model.ResolvedEnvironment;
import cn.refinex.meridian.api.internal.model.SkillContent;
import cn.refinex.meridian.common.context.PlatformContext;
import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.common.error.NotFoundException;
import cn.refinex.meridian.common.net.EndpointPolicy;
import cn.refinex.meridian.engine.spi.spec.EnvironmentSpec;
import cn.refinex.meridian.engine.spi.spec.ResolvedAgentSpec;
import cn.refinex.meridian.engine.spi.spec.SkillDefinition;
import cn.refinex.meridian.runtime.session.app.AgentConfigProvider;
import cn.refinex.meridian.runtime.session.app.TenantSettings;
import cn.refinex.meridian.runtime.session.domain.ConfigUnavailableException;
import cn.refinex.meridian.runtime.session.domain.SessionErrorCode;
import cn.refinex.meridian.runtime.stream.infra.EngineJson;
import cn.refinex.meridian.runtime.tooling.domain.WebSearchSettings;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * {@link AgentConfigProvider} 的生产实现：经 control-plane 的内部接口读取已发布的智能体版本（连同技能内容）、环境修订与租户设置，并缓存
 * （ADR-0047）。
 *
 * <p>缓存分两类。不可变的——按 ID 的已发布版本、指定修订的环境、技能版本——缓存 {@code immutableTtl}（默认 10 分钟），只为删除智能体后
 * 标记更新与控制内存；会变的——智能体的当前版本、环境的当前修订、租户设置——缓存 {@code ttl}（默认 60 秒）。收到 {@code config.invalidate}
 * 时立即淘汰（{@link #evictAgent}、{@link #evictEnvironment}、{@link #evictTenantSettings}），过期时间只是通知丢失时的兜底。
 *
 * <p>归属：资源带租户与项目，绑定了 {@code PlatformContext} 时必须属于其中的项目，否则与不存在相同（D18）；出网授权这类系统路径没有上下文，
 * 不校验（会话行已经确定了它的环境）。另记"智能体版本 → 租户"，租户设置变更时据此找出该租户已装配的实例淘汰（D32）。
 *
 * <p>联网搜索后端的端点在读取时按地址策略再校验一次（D61），不允许时按未配置处理。
 *
 * <p>线程安全：Caffeine 按键计算，同一个键同时只读取一次；方法会阻塞（HTTP），响应式调用方经 {@code Blocking} 调用。
 */
public final class ControlPlaneAgentConfigProvider implements AgentConfigProvider {

    private static final Logger log = LoggerFactory.getLogger(ControlPlaneAgentConfigProvider.class);

    /** "智能体版本 → 租户"的条目上限，远大于实例缓存的 500 个。 */
    static final int VERSION_TENANT_LIMIT = 10_000;

    /** "智能体版本 → 租户"的空闲时间：大于实例缓存的空闲时间（30 分钟），实例还在时映射一定还在。 */
    static final Duration VERSION_TENANT_IDLE = Duration.ofHours(2);

    private final RestClient client;
    private final EndpointPolicy endpoints;
    private final Cache<String, Version> versions;
    private final Cache<String, String> currentVersions;
    private final Cache<String, Environment> environments;
    private final Cache<String, Long> currentEnvironments;
    private final Cache<String, Skill> skills;
    private final Cache<String, TenantSettings> tenantSettings;
    private final Cache<String, String> versionTenants;

    /**
     * 创建实现。
     *
     * @param client 调 control-plane 内部接口的客户端（带服务 JWT）
     * @param endpoints 端点策略，校验联网搜索后端
     * @param ttl 会变的配置的缓存时间
     * @param immutableTtl 不可变的配置的缓存时间
     */
    public ControlPlaneAgentConfigProvider(
            RestClient client, EndpointPolicy endpoints, Duration ttl, Duration immutableTtl) {
        this.client = client;
        this.endpoints = endpoints;
        this.versions = Caffeine.newBuilder()
                .expireAfterWrite(immutableTtl)
                .maximumSize(5_000)
                .build();
        this.currentVersions =
                Caffeine.newBuilder().expireAfterWrite(ttl).maximumSize(10_000).build();
        this.environments = Caffeine.newBuilder()
                .expireAfterWrite(immutableTtl)
                .maximumSize(10_000)
                .build();
        this.currentEnvironments =
                Caffeine.newBuilder().expireAfterWrite(ttl).maximumSize(10_000).build();
        this.skills = Caffeine.newBuilder()
                .expireAfterWrite(immutableTtl)
                .maximumSize(5_000)
                .build();
        this.tenantSettings =
                Caffeine.newBuilder().expireAfterWrite(ttl).maximumSize(10_000).build();
        this.versionTenants = Caffeine.newBuilder()
                .expireAfterAccess(VERSION_TENANT_IDLE)
                .maximumSize(VERSION_TENANT_LIMIT)
                .build();
    }

    @Override
    public ResolvedAgentSpec agentVersion(String agentVersionId) {
        return owned(version(agentVersionId)).spec();
    }

    @Override
    public ResolvedAgentSpec agentVersionForNewSession(String agentVersionId) {
        Version version = owned(version(agentVersionId));
        if (version.agentDeleted()) {
            throw agentNotFound(version.spec().agentId());
        }
        return version.spec();
    }

    @Override
    public ResolvedAgentSpec currentAgentVersion(String agentId) {
        String versionId = currentVersions.get(agentId, id -> {
            Version resolved = fetchVersion("/internal/v1/agents/{id}:resolve", id);
            if (resolved == null) {
                return null;
            }
            versions.put(resolved.spec().agentVersionId(), resolved);
            return resolved.spec().agentVersionId();
        });
        if (versionId == null) {
            throw agentNotFound(agentId);
        }
        return agentVersion(versionId);
    }

    @Override
    public EnvironmentSpec environment(String environmentId, long revision) {
        Environment environment =
                environments.get(key(environmentId, revision), k -> fetchEnvironment(environmentId, revision));
        if (environment == null) {
            throw environmentNotFound(environmentId + " 修订号 " + revision);
        }
        return owned(environment).spec();
    }

    @Override
    public EnvironmentSpec currentEnvironment(String environmentId) {
        Long revision = currentEnvironments.get(environmentId, id -> {
            Environment current = fetchEnvironment(id, null);
            if (current == null) {
                return null;
            }
            environments.put(key(id, current.spec().revision()), current);
            return current.spec().revision();
        });
        if (revision == null) {
            throw environmentNotFound(environmentId);
        }
        return environment(environmentId, revision);
    }

    @Override
    public SkillDefinition skill(String skillId, int version) {
        Skill skill = skills.get(key(skillId, version), k -> fetchSkill(skillId, version));
        if (skill == null) {
            throw new NotFoundException(SessionErrorCode.SKILL_NOT_FOUND, "技能不存在：" + skillId + " 版本 " + version);
        }
        return owned(skill).definition();
    }

    @Override
    public TenantSettings tenantSettings(String tenantId) {
        return tenantSettings.get(tenantId, this::fetchTenantSettings);
    }

    /**
     * 智能体发布了新版本或被删除：淘汰它的当前版本与已缓存的版本（删除标记随之更新）。
     *
     * @param agentId 智能体 ID
     */
    public void evictAgent(String agentId) {
        currentVersions.invalidate(agentId);
        versions.asMap().values().removeIf(v -> v.spec().agentId().equals(agentId));
    }

    /**
     * 环境被修改或删除：淘汰它的当前修订（指定修订的配置不可变，保留给运行中的会话）。
     *
     * @param environmentId 环境 ID
     */
    public void evictEnvironment(String environmentId) {
        currentEnvironments.invalidate(environmentId);
    }

    /**
     * 租户设置变更：淘汰缓存的设置，返回该租户近期用过的智能体版本，调用方据此淘汰已装配的实例（租户提示词随实例缓存，D32）。
     *
     * @param tenantId 租户 ID
     * @return 智能体版本 ID
     */
    public Set<String> evictTenantSettings(String tenantId) {
        tenantSettings.invalidate(tenantId);
        return versionTenants.asMap().entrySet().stream()
                .filter(e -> e.getValue().equals(tenantId))
                .map(Map.Entry::getKey)
                .collect(Collectors.toUnmodifiableSet());
    }

    private Version version(String agentVersionId) {
        Version version =
                versions.get(agentVersionId, id -> fetchVersion("/internal/v1/agent-versions/{id}:resolve", id));
        if (version == null) {
            throw new NotFoundException(SessionErrorCode.AGENT_NOT_FOUND, "智能体版本不存在：" + agentVersionId);
        }
        versionTenants.put(agentVersionId, version.tenantId());
        return version;
    }

    /**
     * 解析一个版本；不存在时返回 null（Caffeine 不缓存）。顺带缓存它引用的技能内容。它在 Caffeine 的加载函数中执行，不能写调用方正在
     * 计算的那个缓存（会抛 {@code Recursive update}），由调用方存放结果。
     */
    private Version fetchVersion(String path, String id) {
        ResolvedAgentVersion dto =
                call(() -> client.post().uri(path, id).retrieve().body(ResolvedAgentVersion.class));
        if (dto == null) {
            return null;
        }
        ResolvedAgentSpec spec = EngineJson.convert(dto.getSpec(), ResolvedAgentSpec.class);
        Version version =
                new Version(dto.getTenantId(), dto.getProjectId(), Boolean.TRUE.equals(dto.getAgentDeleted()), spec);
        for (SkillContent skill : dto.getSkills()) {
            skills.put(key(skill.getSkillId(), skill.getVersion()), toSkill(skill));
        }
        return version;
    }

    /** 读取环境的一个修订；不存在时返回 null。与 {@link #fetchVersion} 一样不写缓存，由调用方存放。 */
    private Environment fetchEnvironment(String environmentId, Long revision) {
        ResolvedEnvironment dto = call(() -> client.get()
                .uri(builder -> {
                    builder.path("/internal/v1/environments/{id}");
                    if (revision != null) {
                        builder.queryParam("revision", revision);
                    }
                    return builder.build(environmentId);
                })
                .retrieve()
                .body(ResolvedEnvironment.class));
        if (dto == null) {
            return null;
        }
        EnvironmentSpec spec = new EnvironmentSpec(
                dto.getEnvironmentId(),
                dto.getRevision(),
                "sandbox".equals(dto.getTier().getValue()) ? EnvironmentSpec.Tier.SANDBOX : EnvironmentSpec.Tier.NONE,
                dto.getSize() == null
                        ? null
                        : EnvironmentSpec.SandboxSize.valueOf(dto.getSize().toUpperCase(Locale.ROOT)),
                dto.getEgressAllowlist());
        return new Environment(dto.getTenantId(), dto.getProjectId(), spec);
    }

    private Skill fetchSkill(String skillId, int version) {
        SkillContent dto = call(() -> client.get()
                .uri("/internal/v1/skills/{id}/versions/{version}", skillId, version)
                .retrieve()
                .body(SkillContent.class));
        return dto == null ? null : toSkill(dto);
    }

    private TenantSettings fetchTenantSettings(String tenantId) {
        InternalTenantSettings dto = call(() -> client.get()
                .uri("/internal/v1/tenants/{id}/settings", tenantId)
                .retrieve()
                .body(InternalTenantSettings.class));
        if (dto == null) {
            return TenantSettings.empty(tenantId);
        }
        return new TenantSettings(tenantId, dto.getSystemPrompt(), webSearch(tenantId, dto.getWebSearch()));
    }

    /** 联网搜索后端：端点按地址策略再校验一次（D61），不允许或不合法时按未配置处理并记 WARN。 */
    private WebSearchSettings webSearch(String tenantId, InternalWebSearchSettings dto) {
        if (dto == null) {
            return null;
        }
        Optional<String> violation = endpoints.violation(dto.getBaseUrl());
        if (violation.isPresent()) {
            log.warn("租户的联网搜索后端不被允许，按未配置处理 tenantId={} reason={}", tenantId, violation.get());
            return null;
        }
        try {
            return new WebSearchSettings(
                    WebSearchSettings.Backend.valueOf(
                            dto.getBackend().getValue().toUpperCase(Locale.ROOT)),
                    URI.create(dto.getBaseUrl()),
                    dto.getSecretId());
        } catch (IllegalArgumentException e) {
            log.warn("租户的联网搜索后端不合法，按未配置处理 tenantId={}", tenantId, e);
            return null;
        }
    }

    private static Skill toSkill(SkillContent dto) {
        return new Skill(
                dto.getTenantId(),
                dto.getProjectId(),
                new SkillDefinition(dto.getSkillId(), dto.getVersion(), dto.getName(), new TreeMap<>(dto.getFiles())));
    }

    /** 调一次内部接口：404 返回 null，其他失败抛可重试的 {@link ConfigUnavailableException}。 */
    private static <T> T call(Supplier<T> request) {
        try {
            return request.get();
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND)) {
                return null;
            }
            throw new ConfigUnavailableException("智能体配置暂时无法读取，请稍后重试", e);
        } catch (RestClientException e) {
            throw new ConfigUnavailableException("智能体配置暂时无法读取，请稍后重试", e);
        }
    }

    /** 绑定了 {@code PlatformContext} 时，资源必须属于其中的项目；否则与不存在相同。 */
    private static <T extends Owned> T owned(T resource) {
        Optional<PlatformContext> platform = PlatformContexts.find();
        if (platform.isPresent()
                && (!platform.get().tenantId().equals(resource.tenantId())
                        || !resource.projectId().equals(platform.get().projectId()))) {
            throw resource.notFound();
        }
        return resource;
    }

    private static String key(String id, long number) {
        return id + "#" + number;
    }

    private static NotFoundException agentNotFound(String agentId) {
        return new NotFoundException(SessionErrorCode.AGENT_NOT_FOUND, "智能体不存在：" + agentId);
    }

    private static NotFoundException environmentNotFound(String what) {
        return new NotFoundException(SessionErrorCode.ENVIRONMENT_NOT_FOUND, "环境不存在：" + what);
    }

    /** 带归属的缓存条目。 */
    private interface Owned {

        String tenantId();

        String projectId();

        NotFoundException notFound();
    }

    /** 一个已发布的版本。 */
    private record Version(String tenantId, String projectId, boolean agentDeleted, ResolvedAgentSpec spec)
            implements Owned {

        @Override
        public NotFoundException notFound() {
            return new NotFoundException(SessionErrorCode.AGENT_NOT_FOUND, "智能体版本不存在：" + spec.agentVersionId());
        }
    }

    /** 环境的一个修订。 */
    private record Environment(String tenantId, String projectId, EnvironmentSpec spec) implements Owned {

        @Override
        public NotFoundException notFound() {
            return environmentNotFound(spec.environmentId());
        }
    }

    /** 一个技能版本。 */
    private record Skill(String tenantId, String projectId, SkillDefinition definition) implements Owned {

        @Override
        public NotFoundException notFound() {
            return new NotFoundException(SessionErrorCode.SKILL_NOT_FOUND, "技能不存在：" + definition.skillId());
        }
    }
}
