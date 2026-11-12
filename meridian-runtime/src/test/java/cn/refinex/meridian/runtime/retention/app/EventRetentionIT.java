package cn.refinex.meridian.runtime.retention.app;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.common.json.JsonMappers;
import cn.refinex.meridian.common.time.MutableClock;
import cn.refinex.meridian.persistence.objectstore.ObjectStore;
import cn.refinex.meridian.runtime.session.app.SessionService;
import cn.refinex.meridian.runtime.support.ApiTestBase;
import cn.refinex.meridian.runtime.support.ApiTestKeys;
import cn.refinex.meridian.runtime.support.DbAssertions;
import cn.refinex.meridian.runtime.support.EventFeeder;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import tools.jackson.databind.JsonNode;

/**
 * 事件保留（T17a-A04，ADR-0049）：租户配置的保留天数生效；数据库中早于截止时间的事件被删除，归档文件中的过期事件被剔除（部分过期时
 * 重写文件、全部过期时删除文件）；一次清理写一条审计 {@code session_events.expired}。保留天数由测试替身给出（生产经 control-plane 的内部
 * 接口下发），时钟可控，作业由测试手动驱动。
 */
@Import(EventRetentionIT.Fixture.class)
class EventRetentionIT extends ApiTestBase {

    /** 测试替身：租户 → 保留天数。 */
    static final Map<String, Integer> RETENTION_DAYS = new ConcurrentHashMap<>();

    @Autowired
    EventRetentionSweeper sweeper;

    @Autowired
    EventArchiver archiver;

    @Autowired
    SessionService sessions;

    @Autowired
    ObjectStore objects;

    @Autowired
    MutableClock clock;

    @Autowired
    ApplicationContext context;

    @Test
    void configuredRetentionDaysExpireEventsInTheDatabaseAndInArchives() {
        EventFeeder feeder = new EventFeeder(context);
        // 第 0 天
        String fullyExpired = createSession(null);
        String partlyExpired = createSession(null);
        String databaseOnly = createSession(null);
        feed(feeder, fullyExpired, "a1", "a2");
        feed(feeder, partlyExpired, "b1");
        feed(feeder, databaseOnly, "c1");
        // 第 50 天、第 60 天
        clock.advance(Duration.ofDays(50));
        feed(feeder, partlyExpired, "b2");
        clock.advance(Duration.ofDays(10));
        feed(feeder, databaseOnly, "c2");
        // 第 141 天：前两个会话已 90 天无活动，归档；第三个最近一次活动在第 60 天，不归档
        clock.advance(Duration.ofDays(81));
        archiveUntil(fullyExpired, 2);
        archiveUntil(partlyExpired, 2);
        assertThat(archiveKey(databaseOnly)).isNull();
        feed(feeder, fullyExpired, "a3");
        String partlyKey = archiveKey(partlyExpired);
        String fullyKey = archiveKey(fullyExpired);

        // 保留 200 天：截止时间早于全部事件，什么都不删
        RETENTION_DAYS.put(ApiTestKeys.TENANT, 200);
        sweeper.sweepOnce();
        assertThat(texts(fullyExpired)).containsExactly("a1", "a2", "a3");
        assertThat(texts(partlyExpired)).containsExactly("b1", "b2");
        assertThat(texts(databaseOnly)).containsExactly("c1", "c2");

        // 保留 100 天：截止时间是第 41 天
        RETENTION_DAYS.put(ApiTestKeys.TENANT, 100);
        sweeper.sweepOnce();

        assertThat(texts(fullyExpired)).containsExactly("a3");
        assertThat(archiveKey(fullyExpired)).isNull();
        assertThat(objects.exists(fullyKey)).isFalse();
        assertThat(texts(partlyExpired)).containsExactly("b2");
        String rewritten = archiveKey(partlyExpired);
        assertThat(rewritten).isNotNull().isNotEqualTo(partlyKey);
        assertThat(objects.exists(partlyKey)).isFalse();
        assertThat(objects.exists(rewritten)).isTrue();
        assertThat(texts(databaseOnly)).containsExactly("c2");
        assertThat(seqs(databaseOnly)).containsExactly(2L);

        List<JsonNode> audits = DbAssertions.list("""
                        SELECT payload::text FROM rt.outbox
                         WHERE topic = 'audit' AND payload->>'action' = 'session_events.expired'
                           AND payload->>'tenant_id' = ?
                         ORDER BY created_at
                        """, String.class, ApiTestKeys.TENANT).stream()
                .map(text -> JsonMappers.lenient().readTree(text))
                .toList();
        // 200 天的那次没有删除，不写审计
        JsonNode audit = audits.getLast();
        assertThat(audit.get("retention_days").asInt()).isEqualTo(100);
        assertThat(Instant.parse(audit.get("cutoff").asString()))
                .isEqualTo(clock.instant().minus(Duration.ofDays(100)));
        assertThat(audit.get("resource_type").asString()).isEqualTo("tenant");
        assertThat(audit.get("actor").get("type").asString()).isEqualTo("service");
        // 共用的库中同租户其他测试的会话也会过期：本测试的会话至少贡献 4 条事件、1 个删除的文件、1 个重写的文件
        assertThat(audit.get("events").asLong()).isGreaterThanOrEqualTo(4);
        assertThat(audit.get("archives_deleted").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(audit.get("archives_rewritten").asInt()).isGreaterThanOrEqualTo(1);

        // 再扫一次：没有新活动的会话不再被处理，也不再写审计
        int before = audits.size();
        sweeper.sweepOnce();
        assertThat(DbAssertions.list("""
                        SELECT count(*) FROM rt.outbox
                         WHERE topic = 'audit' AND payload->>'action' = 'session_events.expired'
                           AND payload->>'tenant_id' = ?
                        """, Long.class, ApiTestKeys.TENANT)).containsExactly((long) before);
    }

    private void feed(EventFeeder feeder, String sessionId, String... texts) {
        String mainThread = PlatformContexts.call(
                projectA(), () -> sessions.mainThread(sessionId).id());
        for (String text : texts) {
            feeder.published(sessionId, mainThread, text);
        }
    }

    private void archiveUntil(String sessionId, long seq) {
        for (int i = 0; i < 100; i++) {
            Object archived = DbAssertions.row("SELECT events_archived_seq FROM rt.sessions WHERE id = ?", sessionId)
                    .get("events_archived_seq");
            if (archived != null && ((Number) archived).longValue() == seq) {
                return;
            }
            archiver.archiveOnce();
        }
        throw new AssertionError("会话没有被归档：" + sessionId);
    }

    private static String archiveKey(String sessionId) {
        return (String) DbAssertions.row("SELECT events_archive_key FROM rt.sessions WHERE id = ?", sessionId)
                .get("events_archive_key");
    }

    /** 经公开 API 读出的全部事件的文本。 */
    private List<String> texts(String sessionId) {
        List<String> texts = new ArrayList<>();
        for (JsonNode event : events(sessionId)) {
            texts.add(event.get("data").get("content").get(0).get("text").asString());
        }
        return texts;
    }

    private List<Long> seqs(String sessionId) {
        return events(sessionId).stream()
                .map(event -> event.get("seq").asLong())
                .toList();
    }

    private List<JsonNode> events(String sessionId) {
        List<JsonNode> events = new ArrayList<>();
        api(ApiTestKeys.KEY_A)
                .listEvents(sessionId, "?limit=100")
                .body()
                .get("data")
                .forEach(events::add);
        return events;
    }

    /** 可控时钟与保留天数的替身。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class Fixture {

        @Bean
        @Primary
        MutableClock clock() {
            return MutableClock.at(Instant.now());
        }

        @Bean
        @Primary
        EventRetentionPolicyPort testRetention() {
            return tenantId -> RETENTION_DAYS.getOrDefault(tenantId, 36_500);
        }
    }
}
