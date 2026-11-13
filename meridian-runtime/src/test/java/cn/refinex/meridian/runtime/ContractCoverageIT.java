package cn.refinex.meridian.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.runtime.support.ContractValidator;
import cn.refinex.meridian.testsupport.contract.ContractCoverage;
import java.util.List;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

/**
 * 契约覆盖（R2-A02）：排在全部集成测试之后（{@code junit-platform.properties} 按 {@link Order} 排类），核对公开 API 与内部接口中由
 * runtime 提供的每个操作，都至少有一次交互经过了规范校验（{@link ContractCoverage} 按真实路径记录）。单独运行本类时没有记录，会失败：
 * 它只在完整的 {@code verify} 中有意义。
 */
@Order(Integer.MAX_VALUE)
class ContractCoverageIT {

    /** runtime 提供的内部接口标签，与本模块 POM 中内部接口的 {@code apisToGenerate} 相同。 */
    static final List<String> INTERNAL_TAGS = List.of("InternalModels", "InternalEgress");

    @Test
    void everyRuntimeOperationWasValidatedAgainstTheSpec() {
        assertThat(ContractCoverage.operations(ContractCoverage.PUBLIC, ContractValidator.runtimeTags()))
                .hasSize(26);
        assertThat(ContractCoverage.uncovered(ContractCoverage.PUBLIC, ContractValidator.runtimeTags()))
                .as("公开 API 中没有经过规范校验的 runtime 操作")
                .isEmpty();
        assertThat(ContractCoverage.operations(ContractCoverage.INTERNAL, INTERNAL_TAGS))
                .hasSize(4);
        assertThat(ContractCoverage.uncovered(ContractCoverage.INTERNAL, INTERNAL_TAGS))
                .as("内部接口中没有经过规范校验的 runtime 操作")
                .isEmpty();
    }
}
