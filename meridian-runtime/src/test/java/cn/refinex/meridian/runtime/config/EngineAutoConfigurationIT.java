package cn.refinex.meridian.runtime.config;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.engine.agentscope.assembly.ModelFactory;
import cn.refinex.meridian.engine.agentscope.assembly.SkillRepositoryProvider;
import cn.refinex.meridian.engine.agentscope.assembly.ToolProvider;
import cn.refinex.meridian.engine.agentscope.assembly.WorkspaceFilesystemProvider;
import cn.refinex.meridian.engine.agentscope.middleware.BatchCheckpointMiddleware;
import cn.refinex.meridian.engine.agentscope.middleware.PlatformMiddlewareProvider;
import cn.refinex.meridian.engine.agentscope.testkit.assembly.InMemoryWorkspaceFilesystems;
import cn.refinex.meridian.engine.agentscope.testkit.assembly.RecordingModelFactory;
import cn.refinex.meridian.engine.agentscope.testkit.assembly.StaticSkillRepository;
import cn.refinex.meridian.engine.agentscope.testkit.assembly.StaticToolProvider;
import cn.refinex.meridian.engine.agentscope.testkit.model.ScriptedModel;
import cn.refinex.meridian.engine.agentscope.turn.AgentScopeEngine;
import cn.refinex.meridian.engine.spi.engine.AgentEngine;
import cn.refinex.meridian.engine.spi.engine.input.InboundMessage;
import cn.refinex.meridian.engine.spi.engine.input.MessageContent;
import cn.refinex.meridian.engine.spi.engine.input.TurnInput;
import cn.refinex.meridian.engine.spi.event.UserEvent;
import cn.refinex.meridian.engine.spi.event.UserEvent.Delivery;
import cn.refinex.meridian.persistence.jdbc.PeriodicJob;
import cn.refinex.meridian.runtime.RuntimeApplication;
import cn.refinex.meridian.runtime.dispatch.app.TurnEnqueuer;
import cn.refinex.meridian.runtime.dispatch.domain.NewTurn;
import cn.refinex.meridian.runtime.session.app.TurnWorkerLifecycle;
import cn.refinex.meridian.runtime.stream.app.SessionEventWriter;
import cn.refinex.meridian.runtime.stream.domain.AppendResult;
import cn.refinex.meridian.runtime.stream.domain.EventBatch;
import cn.refinex.meridian.runtime.support.DbAssertions;
import cn.refinex.meridian.runtime.support.TestSessions;
import cn.refinex.meridian.runtime.support.TestSessions.TestSession;
import cn.refinex.meridian.testsupport.SpringContainerProperties;
import io.agentscope.core.shutdown.GracefulShutdownManager;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 内核与 Worker 在 Spring 容器中的装配（{@code EngineAutoConfiguration}）：提供了模型、工具、文件系统、技能仓库四个提供者时，
 * 容器装配出 {@code AgentScopeEngine} 与 Worker 的后台循环；用 {@code dev-config/agents} 中的开发配置，入队的轮次被后台循环
 * 自动认领并执行到结束。没有这些提供者时（T06 的生产环境）不装配内核，见 {@code RuntimeApplicationIT}。
 */
@SpringBootTest(classes = {RuntimeApplication.class, EngineAutoConfigurationIT.Providers.class})
@ActiveProfiles("test")
@DirtiesContext
class EngineAutoConfigurationIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        SpringContainerProperties.postgres(registry);
        SpringContainerProperties.redis(registry);
        SpringContainerProperties.minio(registry);
        // 测试的工作目录是模块目录，仓库根目录的 dev-config 在上一级
        registry.add("meridian.runtime.dev-agent-config.directory", () -> "../dev-config/agents");
    }

    @Autowired
    private ApplicationContext context;

    @Test
    void engineAndWorkerAreAssembledAndRunAnEnqueuedTurn() {
        TurnWorkerLifecycle lifecycle = context.getBean(TurnWorkerLifecycle.class);
        try {
            assertThat(context.getBean(AgentEngine.class)).isInstanceOf(AgentScopeEngine.class);
            assertThat(context.getBean(PlatformMiddlewareProvider.class).middlewares(null))
                    .hasAtLeastOneElementOfType(BatchCheckpointMiddleware.class);
            assertThat(lifecycle.isRunning()).isTrue();
            assertThat(context.getBean("leaseRenewerJob", PeriodicJob.class).getPhase())
                    .as("下线等待期间续租仍在运行")
                    .isLessThan(lifecycle.getPhase());

            TestSession session =
                    TestSessions.create(context.getBean(Clock.class), "agv_dev_assistant_1", "env_dev_none", 1);
            String eventId = ((AppendResult.Appended) context.getBean(SessionEventWriter.class)
                            .append(EventBatch.unfenced(
                                    session.sessionId(),
                                    session.mainThreadId(),
                                    List.of(new UserEvent.Message(
                                            List.of(new MessageContent.Text("你好")), Delivery.QUEUE)))))
                    .events()
                    .getFirst()
                    .id();
            PlatformContexts.run(
                    session.context(),
                    () -> context.getBean(TurnEnqueuer.class)
                            .enqueue(NewTurn.of(
                                    session.tenantId(),
                                    session.projectId(),
                                    session.sessionId(),
                                    session.mainThreadId(),
                                    new TurnInput.NewMessages(
                                            List.of(new InboundMessage(
                                                    eventId, List.of(new MessageContent.Text("你好")))),
                                            null))));

            List<Map<String, Object>> turns = WorkerFixture.await(
                    () -> DbAssertions.rows(
                            "SELECT status, stop_reason FROM rt.turns WHERE session_id = ?", session.sessionId()),
                    rows -> !rows.isEmpty()
                            && "completed".equals(rows.getFirst().get("status")));
            assertThat(turns.getFirst()).containsEntry("stop_reason", "end_turn");
            assertThat(DbAssertions.list(
                            "SELECT status FROM rt.sessions WHERE id = ?", String.class, session.sessionId()))
                    .containsExactly("idle");
        } finally {
            // 停止会触发 AgentScope 的 JVM 级优雅停机：在这里停并恢复，容器关闭时 stop 已是空操作
            lifecycle.stop();
            GracefulShutdownManager.getInstance().resetForTesting();
        }
    }

    /** 四个内核提供者：脚本化模型，没有工具与技能，内存文件系统。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class Providers {

        @Bean
        ModelFactory modelFactory() {
            return new RecordingModelFactory(
                    ScriptedModel.builder().respond(r -> r.text("Spring 中执行完成")).build());
        }

        @Bean
        ToolProvider toolProvider() {
            return StaticToolProvider.always();
        }

        @Bean
        WorkspaceFilesystemProvider workspaceFilesystemProvider() {
            return new InMemoryWorkspaceFilesystems();
        }

        @Bean
        SkillRepositoryProvider skillRepositoryProvider() {
            return StaticSkillRepository.empty();
        }
    }
}
