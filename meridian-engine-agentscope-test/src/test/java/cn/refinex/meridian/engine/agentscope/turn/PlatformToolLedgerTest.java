package cn.refinex.meridian.engine.agentscope.turn;

import static cn.refinex.meridian.engine.agentscope.turn.EngineHarness.SESSION;
import static cn.refinex.meridian.engine.agentscope.turn.EngineHarness.userText;
import static cn.refinex.meridian.engine.agentscope.turn.FileToolScenarioTest.result;
import static cn.refinex.meridian.engine.agentscope.turn.FileToolScenarioTest.text;
import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.engine.agentscope.testkit.model.ScriptedModel;
import cn.refinex.meridian.engine.agentscope.testkit.port.InMemoryToolLedger.Invocation;
import cn.refinex.meridian.engine.agentscope.tools.PlatformTool;
import cn.refinex.meridian.engine.agentscope.tools.RuleSyntax;
import cn.refinex.meridian.engine.agentscope.tools.ToolCall;
import cn.refinex.meridian.engine.agentscope.tools.ToolOutput;
import cn.refinex.meridian.engine.spi.engine.ToolRetry;
import cn.refinex.meridian.engine.spi.engine.TurnContext;
import cn.refinex.meridian.engine.spi.event.EngineEvent;
import cn.refinex.meridian.engine.spi.port.ToolLedgerPort.Status;
import cn.refinex.meridian.engine.spi.spec.PermissionBehavior;
import cn.refinex.meridian.engine.spi.spec.PermissionMode;
import cn.refinex.meridian.engine.spi.spec.PermissionPolicy;
import cn.refinex.meridian.engine.spi.spec.PermissionRule;
import cn.refinex.meridian.engine.spi.spec.PlatformToolNames;
import cn.refinex.meridian.engine.spi.spec.PolicyLayer;
import cn.refinex.meridian.engine.spi.spec.SideEffect;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * {@code PlatformTool} 的账本集成（T10a-A04 的内核部分）：每次调用记一条，幂等键为 {@code session_id:tool_use_id}；
 * {@code non_idempotent} 工具收到幂等键并把结果写进对象存储（结果引用进账本，登记事项 D14）；恢复确认重试时沿用原幂等键；
 * 规则内容匹配与"记住此决定"的建议规则带内容（登记事项 D21）。
 *
 * <p>测试工具以 {@code bash} 的名字经 {@code PlatformToolProvider.with} 登记（新增平台工具的真实路径），副作用等级
 * {@code non_idempotent}，规则内容为命令前缀，不执行任何命令，只记录收到的幂等键。
 */
class PlatformToolLedgerTest {

    /** 有外部副作用的平台工具替身。 */
    static final class FakeShell extends PlatformTool {

        final List<String> keys = new CopyOnWriteArrayList<>();

        FakeShell(ToolServices services) {
            super(
                    new Spec(
                            PlatformToolNames.BASH,
                            "Run a shell command (test double).",
                            Map.of(
                                    "type", "object",
                                    "properties", Map.of("command", Map.of("type", "string")),
                                    "required", List.of("command")),
                            SideEffect.NON_IDEMPOTENT,
                            RuleSyntax.commandPrefix("command"),
                            false),
                    services);
        }

        @Override
        protected ToolOutput execute(ToolCall call) {
            keys.add(call.idempotencyKey());
            return ToolOutput.ok("ran " + call.string("command") + " key=" + call.idempotencyKey());
        }
    }

    /** 登记替身并记住创建出的实例。 */
    static final class Registry {

        final List<FakeShell> created = new CopyOnWriteArrayList<>();

        Map<String, Function<PlatformTool.ToolServices, PlatformTool>> factories() {
            return Map.of(PlatformToolNames.BASH, services -> {
                FakeShell shell = new FakeShell(services);
                created.add(shell);
                return shell;
            });
        }

        FakeShell shell() {
            return created.getLast();
        }
    }

    static PermissionPolicy allowShell() {
        return new PermissionPolicy(
                PermissionMode.DEFAULT,
                List.of(new PermissionRule(
                        PlatformToolNames.BASH, null, PermissionBehavior.ALLOW, PolicyLayer.AGENT_VERSION)));
    }

    @Test
    void nonIdempotentToolsReceiveTheKeyAndTheirResultIsStoredForRecovery() {
        ScriptedModel model = ScriptedModel.builder()
                .respond(r -> r.toolCall("call_c", "bash", Map.of("command", "make deploy")))
                .respond(r -> r.text("部署完成"))
                .build();
        Registry registry = new Registry();
        EngineHarness harness = EngineHarness.withPlatform(
                model, spec -> spec.tools(PlatformToolNames.BASH), List.of(), registry.factories());

        List<EngineEvent> events = harness.run(harness.claim("thr_shell", "trn_1", 1, allowShell()), userText("部署"));

        String expected = "ran make deploy key=" + SESSION + ":call_c";
        assertThat(text(result(events, "call_c"))).isEqualTo(expected);
        assertThat(registry.shell().keys).as("non_idempotent 工具把幂等键传给下游").containsExactly(SESSION + ":call_c");
        Invocation invocation = harness.ledger.invocations().getFirst();
        assertThat(invocation.start().idempotencyKey()).isEqualTo(SESSION + ":call_c");
        assertThat(invocation.start().sideEffect()).isEqualTo(SideEffect.NON_IDEMPOTENT);
        assertThat(invocation.start().threadId()).isEqualTo("thr_shell");
        assertThat(invocation.status()).isEqualTo(Status.SUCCEEDED);
        assertThat(invocation.resultRef()).as("结果引用进入账本").isNotNull();
        assertThat(new String(harness.blobs.get(invocation.resultRef()).block(), StandardCharsets.UTF_8))
                .isEqualTo(expected);
    }

    @Test
    void aConfirmedRetryReusesTheOriginalIdempotencyKey() {
        Registry registry = new Registry();
        EngineHarness harness = EngineHarness.withPlatform(
                ScriptedModel.builder().build(),
                spec -> spec.tools(PlatformToolNames.BASH),
                List.of(),
                registry.factories());
        TurnContext ctx = harness.claim("thr_retry", "trn_2", 2, allowShell());

        ToolRetry.Result retried = harness.engine
                .retryToolCall(
                        ctx, new ToolRetry("call_old", "bash", Map.of("command", "make deploy"), "ses_x:call_old"))
                .block(EngineHarness.TIMEOUT);

        assertThat(retried.succeeded()).isTrue();
        assertThat(registry.shell().keys).containsExactly("ses_x:call_old");
        assertThat(harness.ledger.invocations())
                .singleElement()
                .satisfies(i -> assertThat(i.start().idempotencyKey()).isEqualTo("ses_x:call_old"));
    }

    @Test
    void ruleContentMatchingAndSuggestionsCarryTheContent() {
        Registry registry = new Registry();
        PlatformTool tool = registry.factories()
                .get(PlatformToolNames.BASH)
                .apply(new PlatformTool.ToolServices(
                        new cn.refinex.meridian.engine.agentscope.testkit.port.InMemoryToolLedger(),
                        new cn.refinex.meridian.engine.agentscope.testkit.port.InMemoryBlobStore()));

        assertThat(tool.matchRule(null, Map.of("command", "git status")))
                .as("空规则内容匹配全部调用")
                .isTrue();
        assertThat(tool.matchRule("", Map.of("command", "git status"))).isTrue();
        assertThat(tool.matchRule("git status:*", Map.of("command", "git status --short")))
                .isTrue();
        assertThat(tool.matchRule("git:*", Map.of("command", "gitk"))).isFalse();
        assertThat(tool.generateSuggestions(Map.of("command", "git status --short")))
                .singleElement()
                .satisfies(rule -> {
                    assertThat(rule.toolName()).isEqualTo("bash");
                    assertThat(rule.ruleContent()).isEqualTo("git:*");
                    assertThat(rule.behavior()).isEqualTo(io.agentscope.core.permission.PermissionBehavior.ALLOW);
                });
    }
}
