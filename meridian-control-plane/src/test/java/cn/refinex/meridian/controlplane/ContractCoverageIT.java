package cn.refinex.meridian.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.testsupport.contract.ContractCoverage;
import java.util.List;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

/**
 * 契约覆盖（R2-A02）：排在全部集成测试之后（{@code junit-platform.properties} 按 {@link Order} 排类），核对管理 API 的全部操作、公开 API
 * 与内部接口中由 control-plane 提供的操作，都至少有一次交互经过了规范校验（{@code AdminClient} 按真实路径记进
 * {@link ContractCoverage}）。单独运行本类时没有记录，会失败：它只在完整的 {@code verify} 中有意义。
 */
@Order(Integer.MAX_VALUE)
class ContractCoverageIT {

    /** control-plane 提供的公开标签，与本模块 POM 中公开规范的 {@code apisToGenerate} 相同。 */
    static final List<String> PUBLIC_TAGS = List.of("Agents", "Usage");

    /** control-plane 提供的内部接口标签，与本模块 POM 中内部接口的 {@code apisToGenerate} 相同。 */
    static final List<String> INTERNAL_TAGS = List.of(
            "InternalApiKeys",
            "InternalTenants",
            "InternalServiceTokens",
            "InternalProjectAccess",
            "InternalSecrets",
            "InternalModelCatalog",
            "InternalAgentConfig",
            "InternalPermissionRules",
            "InternalWebhooks");

    @Test
    void everyControlPlaneOperationWasValidatedAgainstTheSpec() {
        assertThat(ContractCoverage.uncovered(ContractCoverage.ADMIN, ContractCoverage.tags(ContractCoverage.ADMIN)))
                .as("管理 API 中没有经过规范校验的操作")
                .isEmpty();
        assertThat(ContractCoverage.operations(ContractCoverage.PUBLIC, PUBLIC_TAGS))
                .hasSize(3);
        assertThat(ContractCoverage.uncovered(ContractCoverage.PUBLIC, PUBLIC_TAGS))
                .as("公开 API 中没有经过规范校验的 control-plane 操作")
                .isEmpty();
        assertThat(ContractCoverage.operations(ContractCoverage.INTERNAL, INTERNAL_TAGS))
                .hasSize(15);
        assertThat(ContractCoverage.uncovered(ContractCoverage.INTERNAL, INTERNAL_TAGS))
                .as("内部接口中没有经过规范校验的 control-plane 操作")
                .isEmpty();
    }
}
