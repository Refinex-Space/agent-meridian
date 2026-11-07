package cn.refinex.meridian.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.engine.spi.port.AgentStateRepository;
import cn.refinex.meridian.engine.spi.port.EventSinkPort;
import cn.refinex.meridian.engine.spi.port.LeaseRegistry;
import cn.refinex.meridian.persistence.jdbc.PeriodicJob;
import cn.refinex.meridian.persistence.outbox.OutboxWriter;
import cn.refinex.meridian.runtime.dispatch.app.DispatchProperties;
import cn.refinex.meridian.runtime.dispatch.app.NodeLeaseRegistry;
import cn.refinex.meridian.runtime.dispatch.domain.NodeId;
import cn.refinex.meridian.runtime.session.infra.system.JdbcAgentStateRepository;
import cn.refinex.meridian.runtime.stream.app.DatabaseEventSink;
import cn.refinex.meridian.runtime.support.DbAssertions;
import cn.refinex.meridian.testsupport.SpringContainerProperties;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.ApplicationContext;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * runtime 应用骨架：{@code test} profile 下完整启动（Flyway 以迁移角色执行、应用以只有 DML 的角色连接、Redis），
 * 健康检查与探针为 UP，engine-spi 端口由 runtime 的实现注入，outbox 写入 {@code rt.outbox}。
 *
 * <p>上下文带后台续租与回收任务（系统时钟），测试结束即关闭，不影响后续使用可控时钟的测试类。
 */
@SpringBootTest(classes = RuntimeApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureWebTestClient
@DirtiesContext
class RuntimeApplicationIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        SpringContainerProperties.postgres(registry);
        SpringContainerProperties.redis(registry);
    }

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private ApplicationContext context;

    @Test
    void healthAndProbesAreUpWithDatabaseAndRedis() {
        webTestClient
                .get()
                .uri("/actuator/health")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo("UP")
                .jsonPath("$.components.db.status")
                .isEqualTo("UP")
                .jsonPath("$.components.redis.status")
                .isEqualTo("UP");
        webTestClient
                .get()
                .uri("/actuator/health/liveness")
                .exchange()
                .expectStatus()
                .isOk();
        webTestClient
                .get()
                .uri("/actuator/health/readiness")
                .exchange()
                .expectStatus()
                .isOk();
    }

    @Test
    void engineSpiPortsAreImplementedByRuntime() {
        assertThat(context.getBean(LeaseRegistry.class)).isInstanceOf(NodeLeaseRegistry.class);
        assertThat(context.getBean(AgentStateRepository.class)).isInstanceOf(JdbcAgentStateRepository.class);
        assertThat(context.getBean(EventSinkPort.class)).isInstanceOf(DatabaseEventSink.class);
        assertThat(context.getBean(NodeId.class)).isEqualTo(new NodeId("test-node"));
    }

    @Test
    void dispatchDefaultsAndBackgroundJobsAreActive() {
        DispatchProperties properties = context.getBean(DispatchProperties.class);

        assertThat(properties.leaseTtl()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.defaultTenantMaxRunning()).isEqualTo(20);
        Map<String, PeriodicJob> jobs = context.getBeansOfType(PeriodicJob.class);
        assertThat(jobs).containsKeys("leaseRenewerJob", "leaseReaperJob", "outboxRelayJob");
        assertThat(jobs.values()).allMatch(PeriodicJob::isRunning);
    }

    @Test
    void outboxWritesIntoTheRuntimeSchema() {
        OutboxWriter outbox = context.getBean(OutboxWriter.class);
        TransactionTemplate transactions = context.getBean(TransactionTemplate.class);

        String id = transactions.execute(status -> outbox.append("audit", Map.of("action", "t05.check")));

        assertThat(DbAssertions.list("SELECT topic FROM rt.outbox WHERE id = ?", String.class, id))
                .containsExactly("audit");
    }
}
