package cn.refinex.meridian.runtime.modelrouting.infra;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.engine.spi.port.ModelGateway.Provider;
import cn.refinex.meridian.runtime.modelrouting.domain.CatalogModel;
import cn.refinex.meridian.runtime.modelrouting.domain.ProviderAccount;
import cn.refinex.meridian.runtime.modelrouting.domain.RateLimit;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 开发期供应商目录的解析（{@code dev-config/providers}）。 */
class FileModelCatalogTest {

    @TempDir
    Path directory;

    @Test
    void modelsInheritTheAccountRateLimitUnlessTheyHaveTheirOwn() throws IOException {
        Files.writeString(directory.resolve("acme.yaml"), """
                kind: provider_account
                provider_account_id: prv_acme
                provider: openai_compatible
                base_url: http://localhost:9999/v1
                secret_ref: ACME_API_KEY
                rate_limit:
                  rpm: 60
                  tpm: 100000
                models:
                  - model_id: mdl_a
                    upstream_model: model-a
                    context_window: 8000
                    max_output_tokens: 512
                  - model_id: mdl_b
                    upstream_model: model-b
                    context_window: 32000
                    max_output_tokens: 1024
                    rate_limit:
                      rpm: 5
                """);
        FileModelCatalog catalog = new FileModelCatalog(directory);

        assertThat(catalog.model("mdl_a")).contains(new CatalogModel("mdl_a", "prv_acme", "model-a", 8_000, 512, null));
        assertThat(catalog.model("mdl_b"))
                .contains(new CatalogModel("mdl_b", "prv_acme", "model-b", 32_000, 1_024, new RateLimit(5, 0)));
        assertThat(catalog.account("prv_acme"))
                .contains(new ProviderAccount(
                        "prv_acme",
                        Provider.OPENAI_COMPATIBLE,
                        "http://localhost:9999/v1",
                        "ACME_API_KEY",
                        new RateLimit(60, 100_000)));
        assertThat(catalog.model("mdl_missing")).isEmpty();
    }

    @Test
    void missingDirectoryHasNoModels() {
        assertThat(new FileModelCatalog(directory.resolve("absent")).model("mdl_a"))
                .isEmpty();
    }

    @Test
    void theRepositoryDevConfigDeclaresTheDefaultModelWithoutCredentials() throws IOException {
        // 测试的工作目录是模块目录，仓库根目录的 dev-config 在上一级
        Path providers = Path.of("..", "dev-config", "providers");
        FileModelCatalog catalog = new FileModelCatalog(providers);

        CatalogModel model = catalog.model("mdl_dev_default").orElseThrow();
        assertThat(model.upstreamModel()).isEqualTo("qwen-plus");
        assertThat(catalog.account(model.providerAccountId()).orElseThrow().secretRef())
                .as("开发配置只写环境变量名")
                .isEqualTo("DASHSCOPE_API_KEY");
        try (var files = Files.list(providers)) {
            assertThat(files.map(file -> {
                        try {
                            return Files.readString(file);
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                    }))
                    .as("开发配置里没有形如密钥的内容")
                    .noneMatch(text -> text.matches("(?s).*sk-[A-Za-z0-9]{16,}.*"));
        }
    }
}
