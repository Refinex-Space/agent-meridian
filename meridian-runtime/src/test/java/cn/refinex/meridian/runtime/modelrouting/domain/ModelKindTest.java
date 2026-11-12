package cn.refinex.meridian.runtime.modelrouting.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** {@link ModelKind}：模型目录的种类。 */
class ModelKindTest {

    @Test
    void modelKindsParseFromConfigurationValues() {
        assertThat(ModelKind.of(null)).isEqualTo(ModelKind.CHAT);
        assertThat(ModelKind.of("embedding")).isEqualTo(ModelKind.EMBEDDING);
        assertThat(ModelKind.RERANK.value()).isEqualTo("rerank");
        assertThatThrownBy(() -> ModelKind.of("vision")).isInstanceOf(IllegalArgumentException.class);
    }
}
