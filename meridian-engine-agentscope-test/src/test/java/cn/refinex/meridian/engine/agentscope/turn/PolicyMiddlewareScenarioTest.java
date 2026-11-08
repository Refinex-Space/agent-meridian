package cn.refinex.meridian.engine.agentscope.turn;

import static cn.refinex.meridian.engine.agentscope.turn.EngineHarness.userText;
import static cn.refinex.meridian.engine.agentscope.turn.FileToolScenarioTest.result;
import static cn.refinex.meridian.engine.agentscope.turn.FileToolScenarioTest.text;
import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.engine.agentscope.testkit.model.ScriptedModel;
import cn.refinex.meridian.engine.agentscope.testkit.tool.RecordingTool;
import cn.refinex.meridian.engine.spi.event.AgentOutputEvent.PermissionOutcome;
import cn.refinex.meridian.engine.spi.event.AgentOutputEvent.ToolResultState;
import cn.refinex.meridian.engine.spi.event.EngineEvent;
import cn.refinex.meridian.engine.spi.spec.PermissionBehavior;
import cn.refinex.meridian.engine.spi.spec.PermissionMode;
import cn.refinex.meridian.engine.spi.spec.PermissionPolicy;
import cn.refinex.meridian.engine.spi.spec.PermissionRule;
import cn.refinex.meridian.engine.spi.spec.PolicyLayer;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 工具策略（架构设计「平台中间件」中 {@code PolicyMiddleware} 的职责，ADR-0034）：不在智能体工具集内的平台工具、
 * {@code wait_async_results} 得到 DENIED 结果且不执行——即使合成的权限放行了它们；同一批中的其他调用照常执行；拒绝结果有事件，
 * 也写进了上下文。
 */
class PolicyMiddlewareScenarioTest {

    @Test
    void callsOutsideTheToolSetAndWaitAsyncResultsAreDeniedWithoutRunning() {
        // 以平台工具名注册、但智能体工具集里没有的工具：模拟装配或配置错误
        RecordingTool webSearch = RecordingTool.echo("web_search");
        RecordingTool echo = RecordingTool.echo("echo");
        ScriptedModel model = ScriptedModel.builder()
                .respond(r -> r.toolCall("call_ws", "web_search", Map.of("text", "天气"))
                        .toolCall("call_echo", "echo", Map.of("text", "你好")))
                .respond(r -> r.toolCall("call_wait", "wait_async_results", Map.of()))
                .respond(r -> r.text("结束"))
                .build();
        EngineHarness harness = EngineHarness.withPlatform(model, spec -> spec, List.of(), webSearch, echo);
        // 合成的权限放行一切（BYPASS 加整工具 allow），策略拒绝规则仍然先判定
        PermissionPolicy allowAll = new PermissionPolicy(
                PermissionMode.BYPASS,
                List.of(
                        new PermissionRule("web_search", null, PermissionBehavior.ALLOW, PolicyLayer.AGENT_VERSION),
                        new PermissionRule("wait_async_results", null, PermissionBehavior.ALLOW, PolicyLayer.SESSION),
                        new PermissionRule("echo", null, PermissionBehavior.ALLOW, PolicyLayer.AGENT_VERSION)));

        List<EngineEvent> events = harness.run(harness.claim("thr_policy", "trn_1", 1, allowAll), userText("查一下"));

        assertThat(result(events, "call_ws").state()).isEqualTo(ToolResultState.DENIED);
        assertThat(result(events, "call_ws").permission()).isEqualTo(PermissionOutcome.DENIED);

        assertThat(webSearch.calls()).as("被策略拒绝的调用不执行").isEmpty();
        assertThat(text(result(events, "call_echo"))).isEqualTo("echo:你好");
        assertThat(result(events, "call_wait").state()).isEqualTo(ToolResultState.DENIED);
        assertThat(result(events, "call_wait").permission()).isEqualTo(PermissionOutcome.DENIED);
        assertThat(model.request(2).toolResults())
                .as("拒绝结果写进了上下文，下一次推理能看到")
                .extracting(r -> r.getId())
                .contains("call_ws", "call_echo", "call_wait");
    }
}
