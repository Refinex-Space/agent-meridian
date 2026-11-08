package cn.refinex.meridian.engine.agentscope.assembly;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.refinex.meridian.engine.agentscope.testkit.assembly.StaticToolProvider;
import cn.refinex.meridian.engine.agentscope.testkit.assembly.TestAssembly;
import cn.refinex.meridian.engine.agentscope.testkit.assembly.TestSpecs;
import cn.refinex.meridian.engine.agentscope.testkit.model.ScriptedModel;
import cn.refinex.meridian.engine.agentscope.testkit.port.InMemoryBlobStore;
import cn.refinex.meridian.engine.agentscope.testkit.port.InMemoryToolLedger;
import cn.refinex.meridian.engine.agentscope.testkit.tool.RecordingTool;
import cn.refinex.meridian.engine.agentscope.tools.PlatformTool;
import cn.refinex.meridian.engine.agentscope.tools.PlatformToolProvider;
import cn.refinex.meridian.engine.spi.spec.PermissionBehavior;
import cn.refinex.meridian.engine.spi.spec.PermissionRule;
import cn.refinex.meridian.engine.spi.spec.PlatformToolNames;
import cn.refinex.meridian.engine.spi.spec.PolicyLayer;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * 工具策略（T10a-A01，登记事项 D02，ADR-0034）：白名单外的非 {@code ToolBase} 工具让装配失败，启动时的探测装配因此让启动
 * 失败；平台工具提供者创建的工具都是 {@link PlatformTool}；{@code wait_async_results} 与工具集外的工具得到整工具拒绝规则。
 */
class ToolPolicyTest {

    /** 不继承 ToolBase 的工具：不进权限引擎。 */
    static final AgentTool RAW = new AgentTool() {
        @Override
        public String getName() {
            return "raw_shell";
        }

        @Override
        public String getDescription() {
            return "绕过权限引擎的工具";
        }

        @Override
        public Map<String, Object> getParameters() {
            return Map.of("type", "object", "properties", Map.of());
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return Mono.just(ToolResultBlock.text("不应执行"));
        }
    };

    @Test
    void assemblingAnAgentWithANonToolBaseToolOutsideTheWhitelistFails() {
        ScriptedModel model = ScriptedModel.builder().build();
        TestAssembly assembly = TestAssembly.builder(model)
                .tools(StaticToolProvider.always(RAW))
                .build();
        AgentProfile profile = AgentProfile.main(TestSpecs.agent().build(), TestSpecs.noneEnvironment());

        assertThatThrownBy(() -> assembly.assembler().assemble(profile))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("raw_shell");
        assertThatThrownBy(() -> assembly.assembler().selfCheck(profile))
                .as("启动自检走同一条装配路径")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Toolkit 自检失败");
    }

    @Test
    void theSelfCheckPassesForTheHarnessWhitelistAndDoesNotTouchTheModelFactory() {
        ScriptedModel model = ScriptedModel.builder().build();
        TestAssembly assembly = TestAssembly.builder(model)
                .tools(StaticToolProvider.always(RecordingTool.echo("echo")))
                .build();
        AgentProfile probe = AgentProfile.main(TestSpecs.agent().planMode(true).build(), TestSpecs.noneEnvironment());

        assembly.assembler().selfCheck(probe);

        assertThat(assembly.modelFactory().requested()).as("探测装配不经模型工厂").isEmpty();
        assertThat(ToolPolicy.NON_TOOLBASE_ALLOWED).containsExactly("load_skill_through_path");
    }

    @Test
    void waitAsyncResultsAndRegisteredToolsOutsideTheToolSetGetPlatformDenyRules() {
        ScriptedModel model = ScriptedModel.builder().build();
        TestAssembly assembly = TestAssembly.builder(model)
                .tools(StaticToolProvider.always(
                        RecordingTool.echo("read_file"), RecordingTool.echo("web_search"), RecordingTool.echo("echo")))
                .build();
        AgentProfile profile =
                AgentProfile.main(TestSpecs.agent().tools("read_file").build(), TestSpecs.noneEnvironment());

        AssembledAgent assembled = assembly.assembler().assemble(profile);
        try {
            assertThat(assembled.agent().getToolkit().getTool("wait_async_results"))
                    .as("2.0.4 把 @Tool 方法包装为 ReflectiveFunctionTool，它是 ToolBase，会进权限引擎")
                    .isInstanceOf(ToolBase.class);
            assertThat(assembled.policyDenyRules())
                    .extracting(
                            PermissionRule::toolName,
                            PermissionRule::ruleContent,
                            PermissionRule::behavior,
                            PermissionRule::layer)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple(
                                    "wait_async_results", null, PermissionBehavior.DENY, PolicyLayer.PLATFORM),
                            org.assertj.core.groups.Tuple.tuple(
                                    "web_search", null, PermissionBehavior.DENY, PolicyLayer.PLATFORM));
        } finally {
            close(assembled);
        }
    }

    private static void close(AssembledAgent assembled) {
        try {
            assembled.close();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void thePlatformToolProviderOnlyCreatesPlatformTools() {
        PlatformToolProvider provider = PlatformToolProvider.withFileTools(
                new PlatformTool.ToolServices(new InMemoryToolLedger(), new InMemoryBlobStore()));
        AgentProfile profile = AgentProfile.main(
                TestSpecs.agent()
                        .tools(PlatformToolNames.ALL.toArray(String[]::new))
                        .build(),
                TestSpecs.noneEnvironment());

        List<AgentTool> tools = provider.tools(profile).tools();

        assertThat(tools)
                .extracting(AgentTool::getName)
                .containsExactly("edit_file", "glob_files", "grep_files", "list_files", "read_file", "write_file");
        assertThat(tools).allSatisfy(tool -> {
            assertThat(tool).isInstanceOf(PlatformTool.class).isInstanceOf(ToolBase.class);
            assertThat(((PlatformTool) tool).sideEffect())
                    .isEqualTo(
                            tool.getName().startsWith("write") || tool.getName().startsWith("edit")
                                    ? cn.refinex.meridian.engine.spi.spec.SideEffect.IDEMPOTENT
                                    : cn.refinex.meridian.engine.spi.spec.SideEffect.NONE);
        });
        assertThat(provider.tools(profile).tools().getFirst())
                .as("每次装配都是新实例，工具绑定各自的智能体实例")
                .isNotSameAs(tools.getFirst());
    }
}
