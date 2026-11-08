package cn.refinex.meridian.runtime.modelrouting.infra;

import cn.refinex.meridian.engine.spi.port.ModelGateway.Provider;
import cn.refinex.meridian.runtime.modelrouting.app.ModelCatalogPort;
import cn.refinex.meridian.runtime.modelrouting.domain.CatalogModel;
import cn.refinex.meridian.runtime.modelrouting.domain.ModelKind;
import cn.refinex.meridian.runtime.modelrouting.domain.ProviderAccount;
import cn.refinex.meridian.runtime.modelrouting.domain.RateLimit;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 从开发配置目录 {@code dev-config/providers} 读取供应商账号与模型目录的 {@link ModelCatalogPort}（T09a；T15a 之后只留给开发与测试）。
 *
 * <p>每个文件是一个 {@code kind: provider_account}：{@code provider_account_id}、{@code provider}（取值同
 * {@code ModelGateway.Provider.value()}，例如 {@code openai_compatible}）、{@code base_url}、{@code secret_ref}（凭据引用，开发实现是
 * 环境变量名，例如 {@code DASHSCOPE_API_KEY}；文件里只写变量名，不写凭据）、{@code rate_limit: {rpm, tpm}}，以及
 * {@code models} 列表（{@code model_id}、{@code upstream_model}、{@code context_window}、{@code max_output_tokens}，可选的
 * {@code rate_limit}、{@code kind}（{@code chat} 默认、{@code embedding}、{@code rerank}）、{@code base_url}（覆盖账号的端点）、
 * {@code max_batch_size}（Embedding 一次请求的输入条数上限））。
 *
 * <p>文档可以带 {@code profiles} 列表，只在其中任一 profile 激活时加载：脚本化模型服务（{@code dev-config/providers/scripted.yaml}）
 * 只在 {@code dev}、{@code test} 中可用。没有 {@code profiles} 的文档总是加载。
 *
 * <p>每次调用都重新读取目录，改了配置不必重启。方法会阻塞。线程安全：无状态。
 */
public final class FileModelCatalog implements ModelCatalogPort {

    /** 供应商账号文档的 {@code kind}。 */
    static final String KIND_PROVIDER_ACCOUNT = "provider_account";

    private final Path directory;
    private final Set<String> activeProfiles;

    /**
     * 创建目录，只加载不限 profile 的文档。
     *
     * @param directory 开发配置目录
     */
    public FileModelCatalog(Path directory) {
        this(directory, Set.of());
    }

    /**
     * 创建目录。
     *
     * @param directory 开发配置目录
     * @param activeProfiles 当前激活的 profile，决定带 {@code profiles} 的文档是否加载
     */
    public FileModelCatalog(Path directory, Set<String> activeProfiles) {
        this.directory = Objects.requireNonNull(directory, "directory");
        this.activeProfiles = Set.copyOf(activeProfiles);
    }

    @Override
    public Optional<CatalogModel> model(String modelId) {
        for (Map<String, Object> account : accounts()) {
            for (Map<?, ?> model : models(account)) {
                if (modelId.equals(model.get("model_id"))) {
                    return Optional.of(new CatalogModel(
                            modelId,
                            YamlDocuments.required(account, "provider_account_id"),
                            YamlDocuments.required(model, "upstream_model"),
                            Math.toIntExact(YamlDocuments.number(model, "context_window", 0)),
                            Math.toIntExact(YamlDocuments.number(model, "max_output_tokens", 0)),
                            model.get("rate_limit") instanceof Map<?, ?> limit ? rateLimit(limit) : null,
                            ModelKind.of((String) model.get("kind")),
                            (String) model.get("base_url"),
                            Math.toIntExact(YamlDocuments.number(
                                    model, "max_batch_size", CatalogModel.DEFAULT_MAX_BATCH_SIZE))));
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public Optional<ProviderAccount> account(String providerAccountId) {
        return accounts().stream()
                .filter(account -> providerAccountId.equals(account.get("provider_account_id")))
                .findFirst()
                .map(account -> new ProviderAccount(
                        providerAccountId,
                        Provider.valueOf(
                                YamlDocuments.required(account, "provider").toUpperCase(Locale.ROOT)),
                        (String) account.get("base_url"),
                        (String) account.get("secret_ref"),
                        account.get("rate_limit") instanceof Map<?, ?> limit ? rateLimit(limit) : RateLimit.UNLIMITED));
    }

    private List<Map<String, Object>> accounts() {
        return YamlDocuments.load(directory).stream()
                .filter(document -> KIND_PROVIDER_ACCOUNT.equals(document.get("kind")))
                .filter(this::activeInProfile)
                .toList();
    }

    private boolean activeInProfile(Map<String, Object> document) {
        if (!(document.get("profiles") instanceof List<?> profiles)) {
            return true;
        }
        return profiles.stream().anyMatch(activeProfiles::contains);
    }

    private static List<Map<?, ?>> models(Map<String, Object> account) {
        if (!(account.get("models") instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(Map.class::isInstance)
                .<Map<?, ?>>map(Map.class::cast)
                .toList();
    }

    private static RateLimit rateLimit(Map<?, ?> limit) {
        return new RateLimit(
                Math.toIntExact(YamlDocuments.number(limit, "rpm", 0)),
                Math.toIntExact(YamlDocuments.number(limit, "tpm", 0)));
    }
}
