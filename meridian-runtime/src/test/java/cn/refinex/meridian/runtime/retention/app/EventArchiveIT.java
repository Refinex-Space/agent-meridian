package cn.refinex.meridian.runtime.retention.app;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.common.time.MutableClock;
import cn.refinex.meridian.persistence.objectstore.ObjectStore;
import cn.refinex.meridian.runtime.session.app.SessionService;
import cn.refinex.meridian.runtime.support.ApiTestBase;
import cn.refinex.meridian.runtime.support.ApiTestKeys;
import cn.refinex.meridian.runtime.support.DbAssertions;
import cn.refinex.meridian.runtime.support.EventFeeder;
import cn.refinex.meridian.runtime.support.SseTestClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 事件归档（T17a-A03，ADR-0049）：会话 90 天无活动后，事件写进对象存储的归档文件、数据库中删除；{@code GET /v1/sessions/{id}/events}
 * 与 SSE 补发（含 {@code Last-Event-ID} 落在归档范围内、{@code ?threads=main}）仍返回完整、按序的事件；归档后的新事件写回数据库，再次归档时
 * 合并并删除旧文件。时钟可控，归档作业由测试手动驱动。
 */
@Import(EventArchiveIT.Fixture.class)
class EventArchiveIT extends ApiTestBase {

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
    void archivedEventsAreReadTransparentlyByTheListAndTheStream() {
        EventFeeder feeder = new EventFeeder(context);
        String sessionId = createSession(null);
        String mainThread = PlatformContexts.call(
                projectA(), () -> sessions.mainThread(sessionId).id());
        for (int i = 1; i <= 5; i++) {
            feeder.published(sessionId, mainThread, "归档前的第 " + i + " 条");
        }
        List<JsonNode> before = allEvents(sessionId);
        assertThat(before).hasSize(5);

        clock.advance(Duration.ofDays(91));
        archiveUntil(sessionId, 5);

        Map<String, Object> archived = archiveColumns(sessionId);
        String firstKey = (String) archived.get("events_archive_key");
        assertThat(firstKey).startsWith("rt/" + ApiTestKeys.TENANT + "/" + sessionId + "/events/archive-5-");
        assertThat(objects.exists(firstKey)).isTrue();
        assertThat(DbAssertions.list(
                        "SELECT count(*) FROM rt.session_events WHERE session_id = ?", Long.class, sessionId))
                .containsExactly(0L);
        assertThat(allEvents(sessionId)).isEqualTo(before);

        // 归档后会话照常可用：新事件写回数据库，读取跨文件与数据库连续
        feeder.published(sessionId, mainThread, "归档后的第 6 条");
        assertThat(api(ApiTestKeys.KEY_A).archiveSession(sessionId, null).status())
                .isEqualTo(200);
        List<JsonNode> merged = allEvents(sessionId);
        assertThat(merged).hasSize(7);
        assertThat(merged.subList(0, 5)).isEqualTo(before);
        assertThat(merged).extracting(event -> event.get("seq").asLong()).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L);
        assertThat(merged.getLast().get("type").asString()).isEqualTo("session.status_terminated");

        // SSE 补发：从头、Last-Event-ID 落在归档范围内、只订阅主线程
        assertThat(stream(sessionId, "", null))
                .extracting(SseTestClient.Frame::seq)
                .containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L);
        assertThat(stream(sessionId, "", "3"))
                .extracting(SseTestClient.Frame::seq)
                .containsExactly(4L, 5L, 6L, 7L);
        assertThat(stream(sessionId, "?threads=main", "0"))
                .extracting(SseTestClient.Frame::seq)
                .containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L);
        List<SseTestClient.Frame> frames = stream(sessionId, "", null);
        for (int i = 0; i < 5; i++) {
            // SSE 帧的时间由 Instant 序列化（保留 6 位小数），列表接口经 OffsetDateTime（去掉末尾的 0）：按时刻比较
            assertThat(normalized(json(frames.get(i).data()))).isEqualTo(normalized(before.get(i)));
        }

        // 再次归档：合并旧文件与数据库中的新事件，删除旧文件
        clock.advance(Duration.ofDays(91));
        archiveUntil(sessionId, 7);
        String secondKey = (String) archiveColumns(sessionId).get("events_archive_key");
        assertThat(secondKey).isNotEqualTo(firstKey).contains("/archive-7-");
        assertThat(objects.exists(firstKey)).isFalse();
        assertThat(DbAssertions.list(
                        "SELECT count(*) FROM rt.session_events WHERE session_id = ?", Long.class, sessionId))
                .containsExactly(0L);
        assertThat(allEvents(sessionId)).isEqualTo(merged);
        assertThat(stream(sessionId, "", "5"))
                .extracting(SseTestClient.Frame::seq)
                .containsExactly(6L, 7L);
    }

    /** 共用的库中其他测试的会话也会被归档：反复执行，直到本会话归档到期望的 seq。 */
    private void archiveUntil(String sessionId, long seq) {
        for (int i = 0; i < 100; i++) {
            Object archivedSeq = archiveColumns(sessionId).get("events_archived_seq");
            if (archivedSeq != null && ((Number) archivedSeq).longValue() == seq) {
                return;
            }
            archiver.archiveOnce();
        }
        throw new AssertionError("会话没有被归档：" + sessionId);
    }

    private static Map<String, Object> archiveColumns(String sessionId) {
        return DbAssertions.row(
                "SELECT events_archive_key, events_archived_seq FROM rt.sessions WHERE id = ?", sessionId);
    }

    /** 分页（每页 2 条）读出全部事件。 */
    private List<JsonNode> allEvents(String sessionId) {
        List<JsonNode> events = new ArrayList<>();
        String after = null;
        while (true) {
            JsonNode page = api(ApiTestKeys.KEY_A)
                    .listEvents(sessionId, "?limit=2" + (after == null ? "" : "&after=" + after))
                    .body();
            page.get("data").forEach(events::add);
            if (!page.get("has_more").asBoolean()) {
                return events;
            }
            after = page.get("next_cursor").asString();
        }
    }

    private List<SseTestClient.Frame> stream(String sessionId, String query, String lastEventId) {
        return api(ApiTestKeys.KEY_A)
                .finiteStream(
                        "GET /v1/sessions/{session_id}/stream",
                        "/v1/sessions/" + sessionId + "/stream" + query,
                        lastEventId);
    }

    private static JsonNode json(String text) {
        return cn.refinex.meridian.common.json.JsonMappers.lenient().readTree(text);
    }

    /** 事件信封的副本，{@code created_at} 换成规范的时刻表示，其余字段原样。 */
    private static JsonNode normalized(JsonNode event) {
        ObjectNode copy = (ObjectNode) event.deepCopy();
        copy.put("created_at", Instant.parse(event.get("created_at").asString()).toString());
        return copy;
    }

    /** 可控时钟。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class Fixture {

        @Bean
        @Primary
        MutableClock clock() {
            return MutableClock.at(Instant.now());
        }
    }
}
