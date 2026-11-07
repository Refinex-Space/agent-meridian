package cn.refinex.meridian.engine.agentscope.events;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.engine.agentscope.events.StopReasonMapping.Kind;
import cn.refinex.meridian.engine.agentscope.events.StopReasonMapping.Outcome;
import cn.refinex.meridian.engine.spi.event.StopReason;
import io.agentscope.core.message.GenerateReason;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 全部 {@link GenerateReason} 都有映射，与架构设计「状态机」中的停止原因表一致（T04-A02）；{@code TOOL_CALLS}
 * 与未知值映射为 error。
 */
class StopReasonMappingTest {

    /** 架构设计「状态机」停止原因表，逐行抄录。 */
    private static final Map<GenerateReason, Outcome> TABLE = new EnumMap<>(Map.ofEntries(
            Map.entry(GenerateReason.MODEL_STOP, new Outcome(Kind.IDLE, StopReason.END_TURN)),
            Map.entry(GenerateReason.STRUCTURED_OUTPUT, new Outcome(Kind.IDLE, StopReason.END_TURN)),
            Map.entry(GenerateReason.TOOL_RETURN_DIRECT, new Outcome(Kind.IDLE, StopReason.END_TURN)),
            Map.entry(GenerateReason.PERMISSION_ASKING, new Outcome(Kind.REQUIRES_ACTION, null)),
            Map.entry(GenerateReason.TOOL_SUSPENDED, new Outcome(Kind.REQUIRES_ACTION, null)),
            Map.entry(GenerateReason.INTERRUPTED, new Outcome(Kind.IDLE, StopReason.INTERRUPTED)),
            Map.entry(GenerateReason.MAX_ITERATIONS, new Outcome(Kind.IDLE, StopReason.MAX_TURNS)),
            Map.entry(GenerateReason.ALL_TOOLS_DENIED, new Outcome(Kind.IDLE, StopReason.TOOLS_DENIED)),
            Map.entry(GenerateReason.REASONING_STOP_REQUESTED, new Outcome(Kind.STOP_REQUESTED, StopReason.END_TURN)),
            Map.entry(GenerateReason.ACTING_STOP_REQUESTED, new Outcome(Kind.STOP_REQUESTED, StopReason.END_TURN)),
            Map.entry(GenerateReason.MIDDLEWARE_STOP_REQUESTED, new Outcome(Kind.STOP_REQUESTED, StopReason.END_TURN)),
            Map.entry(GenerateReason.TOOL_CALLS, new Outcome(Kind.UNEXPECTED, StopReason.ERROR))));

    @Test
    void everyGenerateReasonIsMapped() {
        assertThat(TABLE).as("表格覆盖全部取值").hasSize(GenerateReason.values().length);
        for (GenerateReason reason : GenerateReason.values()) {
            assertThat(StopReasonMapping.of(reason)).as(reason.name()).isEqualTo(TABLE.get(reason));
        }
    }

    @Test
    void missingReasonIsAnError() {
        assertThat(StopReasonMapping.of(null)).isEqualTo(new Outcome(Kind.UNEXPECTED, StopReason.ERROR));
    }
}
