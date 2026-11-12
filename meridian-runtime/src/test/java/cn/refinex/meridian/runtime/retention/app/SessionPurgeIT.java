package cn.refinex.meridian.runtime.retention.app;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.common.context.PlatformContext;
import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.common.time.MutableClock;
import cn.refinex.meridian.engine.spi.driver.SandboxDriver;
import cn.refinex.meridian.engine.spi.driver.SandboxDriver.SandboxRequest;
import cn.refinex.meridian.engine.spi.driver.SandboxHandle;
import cn.refinex.meridian.engine.spi.spec.EnvironmentSpec.SandboxSize;
import cn.refinex.meridian.engine.spi.spec.SkillDefinition;
import cn.refinex.meridian.persistence.jdbc.JdbcValues;
import cn.refinex.meridian.persistence.objectstore.ObjectStore;
import cn.refinex.meridian.runtime.sandbox.app.FakeSandboxDriver;
import cn.refinex.meridian.runtime.sandbox.domain.SandboxObjectKeys;
import cn.refinex.meridian.runtime.sandbox.domain.SandboxRecord;
import cn.refinex.meridian.runtime.sandbox.infra.SessionSandboxRepository;
import cn.refinex.meridian.runtime.stream.app.EventArchives;
import cn.refinex.meridian.runtime.stream.app.ObjectStoreBlobStore;
import cn.refinex.meridian.runtime.support.ApiTestBase;
import cn.refinex.meridian.runtime.support.ApiTestClient.ApiResponse;
import cn.refinex.meridian.runtime.support.ApiTestKeys;
import cn.refinex.meridian.runtime.support.DbAssertions;
import cn.refinex.meridian.runtime.support.RuntimeTestDatabase;
import cn.refinex.meridian.runtime.webhook.app.WebhookDispatcher;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import reactor.core.publisher.Mono;

/**
 * 删除会话的清理（T17a-A02，ADR-0049；登记事项 D30、D35、D37）：删除后数据库的每张会话表、对象存储的全部前缀（大结果、工作区、归档事件、
 * 快照、产物）、沙箱（含会话不知道的残留沙箱）都没有残留；删除与清理完成各有审计；清理等删除通知的 Webhook 投递结束，最长 12 小时；中途
 * 失败后重试能完成。沙箱驱动是内存替身（kind 上的部分见 {@code SessionPurgeKindIT}），清理作业由测试手动驱动。
 */
@Import(SessionPurgeIT.Fixture.class)
class SessionPurgeIT extends ApiTestBase {

    @Autowired
    SessionPurger purger;

    @Autowired
    WebhookDispatcher webhooks;

    @Autowired
    FlakyDriver driver;

    @Autowired
    ObjectStore objects;

    @Autowired
    EventArchives archives;

    @Autowired
    MutableClock clock;

    @BeforeEach
    void reset() {
        // 别的测试留下的 Webhook outbox 与本测试无关（共用的库中别的测试删除的会话也会被一并清理，断言只看本测试的会话）
        DbAssertions.update("DELETE FROM rt.webhook_events");
    }

    @Test
    void deletedSessionsLeaveNothingBehindAndAFailedPurgeIsRetried() {
        String sessionId = createSession("u_purge");
        assertThat(api(ApiTestKeys.KEY_A).sendMessage(sessionId, "你好").status()).isEqualTo(202);
        Map<String, String> keys = seed(sessionId);
        SandboxHandle orphan = driver.fake.orphan(sessionId);
        assertThat(driver.fake.liveFor(sessionId)).isEqualTo(2);

        ApiResponse deleted = api(ApiTestKeys.KEY_A).deleteSession(sessionId);
        assertThat(deleted.status()).isEqualTo(202);
        assertThat(deleted.text("deleted_at")).isNotNull();
        assertThat(audits(sessionId, "session.deleted"))
                .singleElement()
                .satisfies(audit ->
                        assertThat(audit).contains("\"type\": \"api_key\"").contains("\"subject\": \"key_test_a\""));

        // 删除通知（session.status_terminated）还在 Webhook 的 outbox 里：先不清理
        purger.purgeOnce();
        assertThat(api(ApiTestKeys.KEY_A).getSession(sessionId).status()).isEqualTo(200);
        webhooks.runOnce();

        // 中途失败：沙箱已删，按标签删除残留沙箱时出错；会话还在，可以重试
        driver.failNext.set(true);
        purger.purgeOnce();
        assertThat(api(ApiTestKeys.KEY_A).getSession(sessionId).status()).isEqualTo(200);
        assertThat(driver.fake.liveFor(sessionId)).isEqualTo(1);

        purger.purgeOnce();

        assertThat(api(ApiTestKeys.KEY_A).getSession(sessionId).status()).isEqualTo(404);
        assertThat(driver.fake.liveFor(sessionId))
                .as("残留沙箱 %s", orphan.sandboxId())
                .isZero();
        for (String table : sessionTables()) {
            assertThat(DbAssertions.list(
                            "SELECT count(*) FROM rt." + table + " WHERE session_id = ?", Long.class, sessionId))
                    .as(table)
                    .containsExactly(0L);
        }
        assertThat(DbAssertions.list("SELECT count(*) FROM rt.sessions WHERE id = ?", Long.class, sessionId))
                .containsExactly(0L);
        assertThat(DbAssertions.list(
                        "SELECT count(*) FROM rt.workspace_entries WHERE namespace[4] = ?", Long.class, sessionId))
                .containsExactly(0L);
        keys.forEach((what, key) -> assertThat(objects.exists(key)).as(what).isFalse());
        assertThat(audits(sessionId, "session.purged"))
                .singleElement()
                .satisfies(audit -> assertThat(audit)
                        .contains("\"type\": \"service\"")
                        .contains("\"resource_id\": \"" + sessionId + "\""));
    }

    @Test
    void purgeStopsWaitingForWebhooksAfterTheGracePeriod() {
        String sessionId = createSession(null);
        api(ApiTestKeys.KEY_A).deleteSession(sessionId);
        assertThat(DbAssertions.list(
                        "SELECT count(*) FROM rt.webhook_events WHERE session_id = ?", Long.class, sessionId))
                .containsExactly(1L);
        purger.purgeOnce();
        assertThat(api(ApiTestKeys.KEY_A).getSession(sessionId).status()).isEqualTo(200);

        clock.advance(Duration.ofHours(12).plusMinutes(1));

        purger.purgeOnce();
        assertThat(api(ApiTestKeys.KEY_A).getSession(sessionId).status()).isEqualTo(404);
        assertThat(DbAssertions.list(
                        "SELECT count(*) FROM rt.webhook_events WHERE session_id = ?", Long.class, sessionId))
                .containsExactly(0L);
    }

    /** 给会话铺上各类数据：沙箱、快照、产物、大结果、工作区（内联与对象存储）、归档文件。返回对象键。 */
    private Map<String, String> seed(String sessionId) {
        String tenant = ApiTestKeys.TENANT;
        String project = ApiTestKeys.PROJECT_A;
        PlatformContext context = PlatformContext.builder()
                .tenantId(tenant)
                .projectId(project)
                .sessionId(sessionId)
                .build();
        SandboxHandle handle = driver.fake
                .create(new SandboxRequest(sessionId, SandboxSize.SMALL))
                .block();
        PlatformContexts.run(
                context,
                () -> new SessionSandboxRepository(RuntimeTestDatabase.dataSource())
                        .saveSandbox(
                                sessionId,
                                SandboxRecord.of(handle, SandboxSize.SMALL),
                                handle.podIp(),
                                clock.instant(),
                                clock.instant()));
        String snapshot = SandboxObjectKeys.snapshotsPrefix(tenant, project, sessionId) + "s1.tar";
        String output = SandboxObjectKeys.output(tenant, project, sessionId, "report.docx");
        String blob = ObjectStoreBlobStore.sessionPrefix(tenant, sessionId) + "blobs/result-1";
        String workspaceObject = ObjectStoreBlobStore.sessionPrefix(tenant, sessionId) + "workspace/big-1";
        // 更深的命名空间（子目录）里的大内容键不以会话 ID 结尾，按工作区条目记下的键删除
        String nestedObject = "rt/" + tenant + "/sub/workspace/big-2";
        for (String key : List.of(snapshot, output, blob, workspaceObject, nestedObject)) {
            objects.put(key, "x".getBytes(StandardCharsets.UTF_8), "application/octet-stream");
        }
        String archive = archives.write(tenant, sessionId, 0, List.of());
        DbAssertions.update("""
                INSERT INTO rt.sandbox_outputs (session_id, path, tenant_id, project_id, size, sha256, object_key, synced_at)
                VALUES (?, 'report.docx', ?, ?, 1, 'ab', ?, ?)
                """, sessionId, tenant, project, output, JdbcValues.timestamptz(clock.instant()));
        insertWorkspace(new String[] {tenant, project, "u_purge", sessionId}, "notes.md", "{\"a\":1}", null);
        insertWorkspace(new String[] {tenant, project, "u_purge", sessionId}, "big.md", null, workspaceObject);
        insertWorkspace(new String[] {tenant, project, "u_purge", sessionId, "sub"}, "deep.md", null, nestedObject);
        return Map.of(
                "snapshot", snapshot,
                "output", output,
                "blob", blob,
                "workspace", workspaceObject,
                "nested workspace", nestedObject,
                "archive", archive);
    }

    private void insertWorkspace(String[] namespace, String key, String inline, String objectKey) {
        DbAssertions.update(
                """
                INSERT INTO rt.workspace_entries (namespace, key, tenant_id, project_id, version, size, inline_content,
                                                  object_key, created_at, updated_at)
                VALUES (string_to_array(?, '|'), ?, ?, ?, 1, 1, CAST(? AS jsonb), ?, ?, ?)
                """,
                String.join("|", namespace),
                key,
                namespace[0],
                namespace[1],
                inline,
                objectKey,
                JdbcValues.timestamptz(clock.instant()),
                JdbcValues.timestamptz(clock.instant()));
    }

    /** {@code rt} 中带 {@code session_id} 列的表（不含分区）。 */
    private static List<String> sessionTables() {
        List<String> tables = DbAssertions.list("""
                SELECT c.table_name FROM information_schema.columns c
                  JOIN pg_class t ON t.relname = c.table_name
                  JOIN pg_namespace n ON n.oid = t.relnamespace AND n.nspname = c.table_schema
                 WHERE c.table_schema = 'rt' AND c.column_name = 'session_id' AND NOT t.relispartition
                 ORDER BY c.table_name
                """, String.class);
        assertThat(tables)
                .contains(
                        "threads",
                        "turns",
                        "session_events",
                        "inbox_messages",
                        "tool_invocations",
                        "pending_actions",
                        "permission_rules",
                        "sandbox_outputs",
                        "webhook_events",
                        "webhook_deliveries");
        return tables;
    }

    private static List<String> audits(String sessionId, String action) {
        return DbAssertions.list(
                "SELECT payload::text FROM rt.outbox WHERE topic = 'audit' AND payload->>'action' = ?"
                        + " AND payload->>'resource_id' = ?",
                String.class,
                action,
                sessionId);
    }

    /** 第一次按标签删除可以注入失败的沙箱驱动。 */
    static final class FlakyDriver implements SandboxDriver {

        final FakeSandboxDriver fake;
        final AtomicBoolean failNext = new AtomicBoolean();

        FlakyDriver(FakeSandboxDriver fake) {
            this.fake = fake;
        }

        @Override
        public Mono<SandboxHandle> create(SandboxRequest request) {
            return fake.create(request);
        }

        @Override
        public Mono<SandboxHandle> resume(String state) {
            return fake.resume(state);
        }

        @Override
        public Mono<SandboxHandle> restore(SandboxRequest request, String snapshotKey) {
            return fake.restore(request, snapshotKey);
        }

        @Override
        public Mono<String> snapshot(SandboxHandle handle) {
            return fake.snapshot(handle);
        }

        @Override
        public Mono<Void> delete(SandboxHandle handle) {
            return fake.delete(handle);
        }

        @Override
        public Mono<Integer> deleteSessionSandboxes(String sessionId) {
            if (failNext.getAndSet(false)) {
                return Mono.error(new IllegalStateException("注入的故障：集群暂时不可用"));
            }
            return fake.deleteSessionSandboxes(sessionId);
        }

        @Override
        public Mono<Boolean> syncSkills(SandboxHandle handle, List<SkillDefinition> skills) {
            return fake.syncSkills(handle, skills);
        }

        @Override
        public Mono<Void> writeFiles(SandboxHandle handle, Map<String, byte[]> files) {
            return fake.writeFiles(handle, files);
        }

        @Override
        public Mono<List<OutputFile>> listOutputs(SandboxHandle handle) {
            return fake.listOutputs(handle);
        }

        @Override
        public Mono<byte[]> readOutput(SandboxHandle handle, String path) {
            return fake.readOutput(handle, path);
        }
    }

    /** 可控时钟与内存沙箱驱动。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class Fixture {

        @Bean
        @Primary
        MutableClock clock() {
            return MutableClock.at(Instant.now());
        }

        @Bean
        FlakyDriver flakyDriver(ObjectStore objects) {
            return new FlakyDriver(new FakeSandboxDriver(objects));
        }
    }
}
