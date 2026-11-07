package cn.refinex.meridian.engine.agentscope.testkit.assembly;

import cn.refinex.meridian.engine.agentscope.assembly.AgentAssembler;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import java.util.Objects;

/**
 * 用测试替身组装的 {@link AgentAssembler}，并保留各个替身供断言。
 *
 * <pre>{@code
 * TestAssembly assembly = TestAssembly.builder(scriptedModel)
 *         .tools(StaticToolProvider.always(RecordingTool.echo("echo")))
 *         .build();
 * AssembledAgent agent = assembly.assembler().assemble(AgentProfile.main(spec, environment));
 * }</pre>
 *
 * <p>见架构设计「测试策略」「引擎装配」。
 *
 * @param assembler 装配器
 * @param modelFactory 模型工厂替身
 * @param stateStore 状态存储，默认 AgentScope 的 {@link InMemoryAgentStateStore}
 * @param middlewares 中间件提供者替身
 * @param tools 工具提供者替身
 * @param filesystems 文件系统提供者替身
 * @param skills 技能仓库替身
 */
public record TestAssembly(
        AgentAssembler assembler,
        RecordingModelFactory modelFactory,
        AgentStateStore stateStore,
        RecordingMiddlewareProvider middlewares,
        StaticToolProvider tools,
        InMemoryWorkspaceFilesystems filesystems,
        StaticSkillRepository skills) {

    /**
     * 新建构建器，所有模型引用都返回给定模型。
     *
     * @param model 模型，通常是 {@code ScriptedModel}
     * @return 构建器
     */
    public static Builder builder(Model model) {
        return new Builder(new RecordingModelFactory(model));
    }

    /** {@link TestAssembly} 的构建器，没有指定的部件取默认的替身。 */
    public static final class Builder {

        private final RecordingModelFactory modelFactory;
        private AgentStateStore stateStore = new InMemoryAgentStateStore();
        private RecordingMiddlewareProvider middlewares = RecordingMiddlewareProvider.none();
        private StaticToolProvider tools = StaticToolProvider.always();
        private InMemoryWorkspaceFilesystems filesystems = new InMemoryWorkspaceFilesystems();
        private StaticSkillRepository skills = StaticSkillRepository.empty();

        private Builder(RecordingModelFactory modelFactory) {
            this.modelFactory = modelFactory;
        }

        /**
         * 指定状态存储。
         *
         * @param value 状态存储
         * @return 本构建器
         */
        public Builder stateStore(AgentStateStore value) {
            this.stateStore = Objects.requireNonNull(value);
            return this;
        }

        /**
         * 指定中间件提供者。
         *
         * @param value 提供者
         * @return 本构建器
         */
        public Builder middlewares(RecordingMiddlewareProvider value) {
            this.middlewares = Objects.requireNonNull(value);
            return this;
        }

        /**
         * 指定工具提供者。
         *
         * @param value 提供者
         * @return 本构建器
         */
        public Builder tools(StaticToolProvider value) {
            this.tools = Objects.requireNonNull(value);
            return this;
        }

        /**
         * 指定文件系统提供者。
         *
         * @param value 提供者
         * @return 本构建器
         */
        public Builder filesystems(InMemoryWorkspaceFilesystems value) {
            this.filesystems = Objects.requireNonNull(value);
            return this;
        }

        /**
         * 指定技能仓库。
         *
         * @param value 技能仓库
         * @return 本构建器
         */
        public Builder skills(StaticSkillRepository value) {
            this.skills = Objects.requireNonNull(value);
            return this;
        }

        /**
         * 组装。
         *
         * @return 装配器与替身
         */
        public TestAssembly build() {
            AgentAssembler assembler =
                    new AgentAssembler(modelFactory, stateStore, middlewares, tools, filesystems, skills);
            return new TestAssembly(assembler, modelFactory, stateStore, middlewares, tools, filesystems, skills);
        }
    }
}
