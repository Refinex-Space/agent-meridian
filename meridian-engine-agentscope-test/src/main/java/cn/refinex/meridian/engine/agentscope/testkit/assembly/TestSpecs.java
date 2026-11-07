package cn.refinex.meridian.engine.agentscope.testkit.assembly;

import cn.refinex.meridian.engine.spi.spec.CompactionSpec;
import cn.refinex.meridian.engine.spi.spec.EnvironmentSpec;
import cn.refinex.meridian.engine.spi.spec.KnowledgeSpec;
import cn.refinex.meridian.engine.spi.spec.McpServerSpec;
import cn.refinex.meridian.engine.spi.spec.ModelSpec;
import cn.refinex.meridian.engine.spi.spec.PermissionPolicy;
import cn.refinex.meridian.engine.spi.spec.ResolvedAgentSpec;
import cn.refinex.meridian.engine.spi.spec.SkillRef;
import cn.refinex.meridian.engine.spi.spec.SubagentSpec;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 测试用的配置工厂：智能体版本配置的构建器（带合理的默认值）与两个档位的环境配置。
 */
public final class TestSpecs {

    private TestSpecs() {}

    /**
     * 智能体版本配置的构建器，默认没有工具、没有子智能体、{@code maxIters} 取默认值。
     *
     * @return 构建器
     */
    public static AgentSpecBuilder agent() {
        return new AgentSpecBuilder();
    }

    /**
     * {@code none} 档环境，修订号 1。
     *
     * @return 环境配置
     */
    public static EnvironmentSpec noneEnvironment() {
        return new EnvironmentSpec("env_test_none", 1, EnvironmentSpec.Tier.NONE, null, List.of());
    }

    /**
     * {@code sandbox} 档 {@code small} 规格的环境，修订号 1。
     *
     * @return 环境配置
     */
    public static EnvironmentSpec sandboxEnvironment() {
        return new EnvironmentSpec(
                "env_test_sandbox", 1, EnvironmentSpec.Tier.SANDBOX, EnvironmentSpec.SandboxSize.SMALL, List.of());
    }

    /** {@link ResolvedAgentSpec} 的构建器。 */
    public static final class AgentSpecBuilder {

        private String agentVersionId = "agv_test";
        private String name = "assistant";
        private ModelSpec model = ModelSpec.of("mdl_test");
        private String systemPrompt = "你是测试助手。";
        private final Set<String> tools = new LinkedHashSet<>();
        private final List<McpServerSpec> mcpServers = new ArrayList<>();
        private final List<SkillRef> skills = new ArrayList<>();
        private final List<SubagentSpec> subagents = new ArrayList<>();
        private PermissionPolicy permissions = PermissionPolicy.empty();
        private CompactionSpec compaction = CompactionSpec.defaults();
        private boolean planMode;
        private KnowledgeSpec knowledge = KnowledgeSpec.none();
        private int maxIters = ResolvedAgentSpec.DEFAULT_MAX_ITERS;
        private boolean exposeThinking;

        private AgentSpecBuilder() {}

        /**
         * 智能体版本 ID。
         *
         * @param value ID
         * @return 本构建器
         */
        public AgentSpecBuilder agentVersionId(String value) {
            this.agentVersionId = value;
            return this;
        }

        /**
         * 智能体名。
         *
         * @param value 名字
         * @return 本构建器
         */
        public AgentSpecBuilder name(String value) {
            this.name = value;
            return this;
        }

        /**
         * 模型引用。
         *
         * @param value 模型引用
         * @return 本构建器
         */
        public AgentSpecBuilder model(ModelSpec value) {
            this.model = value;
            return this;
        }

        /**
         * 系统提示词。
         *
         * @param value 提示词
         * @return 本构建器
         */
        public AgentSpecBuilder systemPrompt(String value) {
            this.systemPrompt = value;
            return this;
        }

        /**
         * 追加平台工具名。
         *
         * @param names 工具名
         * @return 本构建器
         */
        public AgentSpecBuilder tools(String... names) {
            tools.addAll(List.of(names));
            return this;
        }

        /**
         * 追加子智能体。
         *
         * @param value 子智能体配置
         * @return 本构建器
         */
        public AgentSpecBuilder subagent(SubagentSpec value) {
            subagents.add(value);
            return this;
        }

        /**
         * 追加技能。
         *
         * @param value 技能引用
         * @return 本构建器
         */
        public AgentSpecBuilder skill(SkillRef value) {
            skills.add(value);
            return this;
        }

        /**
         * 智能体层的权限策略。
         *
         * @param value 策略
         * @return 本构建器
         */
        public AgentSpecBuilder permissions(PermissionPolicy value) {
            this.permissions = value;
            return this;
        }

        /**
         * 压缩配置。
         *
         * @param value 压缩配置
         * @return 本构建器
         */
        public AgentSpecBuilder compaction(CompactionSpec value) {
            this.compaction = value;
            return this;
        }

        /**
         * 是否启用计划模式。
         *
         * @param value 是否启用
         * @return 本构建器
         */
        public AgentSpecBuilder planMode(boolean value) {
            this.planMode = value;
            return this;
        }

        /**
         * 知识库配置。
         *
         * @param value 知识库配置
         * @return 本构建器
         */
        public AgentSpecBuilder knowledge(KnowledgeSpec value) {
            this.knowledge = value;
            return this;
        }

        /**
         * 单轮最大推理次数。
         *
         * @param value 次数
         * @return 本构建器
         */
        public AgentSpecBuilder maxIters(int value) {
            this.maxIters = value;
            return this;
        }

        /**
         * 是否暴露思考内容。
         *
         * @param value 是否暴露
         * @return 本构建器
         */
        public AgentSpecBuilder exposeThinking(boolean value) {
            this.exposeThinking = value;
            return this;
        }

        /**
         * 构建配置。
         *
         * @return 智能体版本配置
         */
        public ResolvedAgentSpec build() {
            return new ResolvedAgentSpec(
                    "agt_test",
                    agentVersionId,
                    name,
                    model,
                    systemPrompt,
                    tools,
                    mcpServers,
                    skills,
                    subagents,
                    permissions,
                    compaction,
                    planMode,
                    knowledge,
                    maxIters,
                    exposeThinking);
        }
    }
}
