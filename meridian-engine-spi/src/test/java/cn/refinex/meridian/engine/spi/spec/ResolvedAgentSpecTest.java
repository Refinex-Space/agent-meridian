package cn.refinex.meridian.engine.spi.spec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 智能体版本配置在构造时的取值校验：这里是 control-plane 配置进入内核的边界。 */
class ResolvedAgentSpecTest {

    @Test
    void acceptsMaxItersWithinOneToTwoHundred() {
        assertThat(spec(1, Set.of(), List.of(), List.of()).maxIters()).isEqualTo(1);
        assertThat(spec(ResolvedAgentSpec.MAX_ITERS_LIMIT, Set.of(), List.of(), List.of())
                        .maxIters())
                .isEqualTo(200);

        assertThatThrownBy(() -> spec(0, Set.of(), List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxIters");
        assertThatThrownBy(() -> spec(201, Set.of(), List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxIters");
    }

    @Test
    void rejectsUnknownPlatformTool() {
        assertThatThrownBy(() -> spec(50, Set.of("shell_execute"), List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("shell_execute");
    }

    @Test
    void subagentsRequireSpawnSubagentAndSubsetOfParentTools() {
        SubagentSpec reader = subagent("reader", Set.of("read_file", "mcp__crm__query"));
        McpServerSpec crm = mcp("crm", Set.of("query"));

        ResolvedAgentSpec ok = spec(50, Set.of("read_file", "spawn_subagent"), List.of(crm), List.of(reader));
        assertThat(ok.subagent("reader")).contains(reader);
        assertThat(ok.subagent("missing")).isEmpty();

        assertThatThrownBy(() -> spec(50, Set.of("read_file"), List.of(crm), List.of(reader)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("spawn_subagent");
        assertThatThrownBy(() -> spec(50, Set.of("spawn_subagent"), List.of(crm), List.of(reader)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("read_file");
        assertThatThrownBy(() -> spec(
                        50,
                        Set.of("read_file", "spawn_subagent"),
                        List.of(mcp("crm", Set.of("update"))),
                        List.of(reader)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mcp__crm__query");
    }

    @Test
    void subagentCannotSpawnAndTimeoutIsCappedAtSixHundredSeconds() {
        assertThatThrownBy(() -> subagent("nested", Set.of("spawn_subagent")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能再拉起");
        assertThatThrownBy(() -> new SubagentSpec(
                        "slow",
                        "说明",
                        ModelSpec.of("mdl_1"),
                        "提示词",
                        Set.of(),
                        List.of(),
                        PermissionPolicy.empty(),
                        10,
                        Duration.ofSeconds(601)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("600");
    }

    @Test
    void environmentSizeMustMatchTier() {
        assertThatThrownBy(() -> new EnvironmentSpec("env_1", 1, EnvironmentSpec.Tier.SANDBOX, null, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EnvironmentSpec(
                        "env_1", 1, EnvironmentSpec.Tier.NONE, EnvironmentSpec.SandboxSize.SMALL, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new EnvironmentSpec("env_1", 3, EnvironmentSpec.Tier.NONE, null, null).egressAllowlist())
                .isEmpty();
    }

    private static ResolvedAgentSpec spec(
            int maxIters, Set<String> tools, List<McpServerSpec> mcpServers, List<SubagentSpec> subagents) {
        return new ResolvedAgentSpec(
                "agt_1",
                "agv_1",
                "assistant",
                ModelSpec.of("mdl_1"),
                "你是助手",
                tools,
                mcpServers,
                List.of(),
                subagents,
                PermissionPolicy.empty(),
                CompactionSpec.defaults(),
                false,
                KnowledgeSpec.none(),
                maxIters,
                false);
    }

    private static SubagentSpec subagent(String name, Set<String> tools) {
        return new SubagentSpec(
                name,
                "说明",
                ModelSpec.of("mdl_1"),
                "提示词",
                tools,
                List.of(),
                PermissionPolicy.empty(),
                10,
                Duration.ofSeconds(60));
    }

    private static McpServerSpec mcp(String name, Set<String> enabledTools) {
        return new McpServerSpec(
                "mcp_1",
                name,
                "https://mcp.example.com/" + name,
                McpServerSpec.McpTransport.STREAMABLE_HTTP,
                null,
                SideEffect.NONE,
                enabledTools);
    }
}
