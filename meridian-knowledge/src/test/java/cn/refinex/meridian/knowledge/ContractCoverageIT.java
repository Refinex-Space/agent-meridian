package cn.refinex.meridian.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.testsupport.contract.ContractCoverage;
import java.util.List;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

/**
 * 契约覆盖（R2-A02）：排在全部集成测试之后（{@code junit-platform.properties} 按 {@link Order} 排类），核对公开 API 与内部接口中由
 * knowledge 提供的每个操作，都至少有一次交互经过了规范校验（{@code ApiClient} 按真实路径记进 {@link ContractCoverage}）。单独运行本类时
 * 没有记录，会失败：它只在完整的 {@code verify} 中有意义。
 */
@Order(Integer.MAX_VALUE)
class ContractCoverageIT {

    @Test
    void everyKnowledgeOperationWasValidatedAgainstTheSpec() {
        List<String> publicTags = List.of("KnowledgeBases");
        List<String> internalTags = List.of("InternalKnowledge");
        assertThat(ContractCoverage.operations(ContractCoverage.PUBLIC, publicTags))
                .hasSize(11);
        assertThat(ContractCoverage.uncovered(ContractCoverage.PUBLIC, publicTags))
                .as("公开 API 中没有经过规范校验的 knowledge 操作")
                .isEmpty();
        assertThat(ContractCoverage.operations(ContractCoverage.INTERNAL, internalTags))
                .hasSize(2);
        assertThat(ContractCoverage.uncovered(ContractCoverage.INTERNAL, internalTags))
                .as("内部接口中没有经过规范校验的 knowledge 操作")
                .isEmpty();
    }
}
