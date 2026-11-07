package cn.refinex.meridian.runtime.config;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.engine.agentscope.assembly.ModelFactory;
import cn.refinex.meridian.engine.agentscope.assembly.SkillRepositoryProvider;
import cn.refinex.meridian.engine.agentscope.assembly.ToolProvider;
import cn.refinex.meridian.engine.agentscope.assembly.WorkspaceFilesystemProvider;
import cn.refinex.meridian.engine.agentscope.testkit.assembly.InMemoryWorkspaceFilesystems;
import cn.refinex.meridian.engine.agentscope.testkit.assembly.RecordingModelFactory;
import cn.refinex.meridian.engine.agentscope.testkit.assembly.StaticSkillRepository;
import cn.refinex.meridian.engine.agentscope.testkit.assembly.StaticToolProvider;
import cn.refinex.meridian.engine.agentscope.testkit.model.ScriptedModel;
import cn.refinex.meridian.runtime.RuntimeApplication;
import cn.refinex.meridian.runtime.session.app.TurnWorkerLifecycle;
import cn.refinex.meridian.runtime.support.ApiTestClient;
import cn.refinex.meridian.runtime.support.ApiTestClient.ApiResponse;
import cn.refinex.meridian.runtime.support.ApiTestKeys;
import cn.refinex.meridian.runtime.support.SseTestClient;
import cn.refinex.meridian.runtime.support.SseTestClient.Connection;
import cn.refinex.meridian.runtime.support.SseTestClient.Frame;
import cn.refinex.meridian.testsupport.SpringContainerProperties;
import io.agentscope.core.shutdown.GracefulShutdownManager;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 端到端（T07-A02）：用 HTTP 创建会话、发送 {@code user.message}、订阅 SSE，收到从 {@code session.status_running} 到
 * {@code session.status_idle} 的完整事件序列。内核用 {@code ScriptedModel}，Worker 在同一个 Spring 容器里执行轮次。
 */
@SpringBootTest(
        classes = {RuntimeApplication.class, SessionApiEndToEndIT.Providers.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DirtiesContext
class SessionApiEndToEndIT {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        SpringContainerProperties.postgres(registry);
        SpringContainerProperties.redis(registry);
        SpringContainerProperties.minio(registry);
        ApiTestKeys.register(registry);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TurnWorkerLifecycle lifecycle;

    @AfterEach
    void stopWorker() {
        // 停止会触发 AgentScope 的 JVM 级优雅停机：在这里停并恢复，容器关闭时 stop 已是空操作
        lifecycle.stop();
        GracefulShutdownManager.getInstance().resetForTesting();
    }

    @Test
    void messageOverHttpRunsATurnAndStreamsItFromRunningToIdle() {
        ApiTestClient api = new ApiTestClient(port, ApiTestKeys.KEY_A);
        ApiResponse created = api.createSession("""
                {"agent": "agt_dev_assistant", "environment": "env_dev_none", "end_user_id": "u_1024",
                 "end_user_attributes": {"acl_tags": ["dept:finance"]}, "metadata": {"ticket": "INC-1"}}
                """, null);
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.text("agent_version_id")).isEqualTo("agv_dev_assistant_1");
        assertThat(created.body().get("environment_revision").asLong()).isEqualTo(1);
        assertThat(created.text("status")).isEqualTo("idle");
        String sessionId = created.text("id");

        Connection stream =
                new SseTestClient(port, ApiTestKeys.KEY_A).open("/v1/sessions/" + sessionId + "/stream", null);
        try {
            ApiResponse sent = api.sendMessage(sessionId, "差旅住宿标准是多少？");
            assertThat(sent.status()).isEqualTo(202);
            assertThat(sent.body().get("data").get(0).get("seq").asLong()).isEqualTo(1);

            List<Frame> frames = stream.await(
                    received -> received.stream().anyMatch(f -> f.event().equals("session.status_idle")),
                    Duration.ofSeconds(30));
            List<String> types = frames.stream().map(Frame::event).toList();
            assertThat(types.getFirst()).isEqualTo("user.message");
            assertThat(types)
                    .containsSubsequence(
                            "user.message", "session.status_running", "agent.message", "session.status_idle");
            assertThat(types.indexOf("session.status_running")).isLessThan(types.indexOf("session.status_idle"));
            Frame idle = frames.stream()
                    .filter(f -> f.event().equals("session.status_idle"))
                    .findFirst()
                    .orElseThrow();
            assertThat(idle.envelope().get("data").get("stop_reason").asString())
                    .isEqualTo("end_turn");
            Frame message = frames.stream()
                    .filter(f -> f.event().equals("agent.message"))
                    .findFirst()
                    .orElseThrow();
            assertThat(message.envelope()
                            .get("data")
                            .get("content")
                            .get(0)
                            .get("text")
                            .asString())
                    .isEqualTo("住宿标准为每晚 500 元。");
            assertThat(stream.seqs()).isEqualTo(rangeClosed(1, stream.seqs().size()));
        } finally {
            stream.close();
        }

        ApiResponse session = api.getSession(sessionId);
        assertThat(session.text("status")).isEqualTo("idle");
        ApiResponse events = api.listEvents(sessionId, "limit=100");
        assertThat(events.body().get("data")).hasSizeGreaterThanOrEqualTo(4);
        assertThat(api.listThreads(sessionId, null)
                        .body()
                        .get("data")
                        .get(0)
                        .get("agent_name")
                        .asString())
                .isEqualTo("assistant");
    }

    private static List<Long> rangeClosed(long from, long to) {
        return java.util.stream.LongStream.rangeClosed(from, to).boxed().toList();
    }

    /** 四个内核提供者：脚本化模型回答一句话，没有工具与技能，内存文件系统。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class Providers {

        @Bean
        ModelFactory modelFactory() {
            return new RecordingModelFactory(ScriptedModel.builder()
                    .respond(r -> r.text("住宿标准为每晚 500 元。"))
                    .build());
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
