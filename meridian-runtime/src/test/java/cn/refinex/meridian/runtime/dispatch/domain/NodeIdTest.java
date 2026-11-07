package cn.refinex.meridian.runtime.dispatch.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** {@link NodeId}：可用于频道名的字符集，每个进程生成不同的 ID。 */
class NodeIdTest {

    @Test
    void generatedIdsStartWithTheHostnameAndDifferPerProcess() {
        NodeId first = NodeId.generate("meridian-runtime-7d9f/abc");
        NodeId second = NodeId.generate("meridian-runtime-7d9f/abc");

        assertThat(first.value()).matches("meridian-runtime-7d9f-abc-[0-9a-f]{8}");
        assertThat(first).isNotEqualTo(second);
        assertThat(NodeId.generate(null).value()).startsWith("runtime-");
    }

    @Test
    void rejectsCharactersUnsafeForChannelNames() {
        assertThatThrownBy(() -> new NodeId("node a")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NodeId("node:a")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NodeId("")).isInstanceOf(IllegalArgumentException.class);
    }
}
