package cn.refinex.meridian.runtime.stream.app;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.common.id.Ids;
import cn.refinex.meridian.engine.spi.event.EventType;
import cn.refinex.meridian.engine.spi.event.StreamEvent;
import cn.refinex.meridian.engine.spi.event.StreamEvent.BlockKind;
import cn.refinex.meridian.persistence.redis.ReactiveRedisMessaging;
import cn.refinex.meridian.runtime.stream.domain.StoredEvent;
import cn.refinex.meridian.runtime.support.RuntimeTestRedis;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

/** 事件的实时扇出：条目字段与信封、只在流上的事件，以及 Redis 不可用时只记指标、不报错（数据库是事实来源）。 */
class EventStreamPublisherIT {

    private final Clock clock = Clock.fixed(Instant.parse("2100-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void persistedAndTransientEventsAreAppendedWithTheirEnvelopes() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        EventStreamPublisher publisher = new EventStreamPublisher(RuntimeTestRedis.reactiveMessaging(), clock, meters);
        String sessionId = "ses_" + Ids.newUlid();
        StoredEvent stored = new StoredEvent(
                sessionId,
                7,
                "evt_1",
                "ten_1",
                "prj_1",
                "thr_1",
                "trn_1",
                EventType.SESSION_STATUS_RUNNING,
                "{}",
                clock.instant());

        publisher.publish(List.of(stored)).block(Duration.ofSeconds(10));
        publisher
                .publishTransient(sessionId, "thr_1", "trn_1", new StreamEvent.EventDelta("b1", BlockKind.TEXT, "你"))
                .block(Duration.ofSeconds(10));

        List<MapRecord<String, Object, Object>> records =
                RuntimeTestRedis.readAll(EventStreamPublisher.streamKey(sessionId));
        assertThat(records).hasSize(2);
        Map<Object, Object> persisted = records.getFirst().getValue();
        assertThat(persisted).containsEntry("type", "session.status_running").containsEntry("seq", "7");
        assertThat((String) persisted.get("envelope"))
                .contains("\"id\":\"evt_1\"", "\"seq\":7", "\"session_id\":\"" + sessionId + "\"", "\"data\":{}");
        Map<Object, Object> delta = records.get(1).getValue();
        assertThat(delta).containsEntry("type", "event_delta").doesNotContainKey("seq");
        assertThat((String) delta.get("envelope")).contains("\"seq\":null", "\"delta\":\"你\"");
    }

    @Test
    void redisFailuresAreCountedAndNeverPropagated() {
        LettuceConnectionFactory unreachable =
                new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", 1));
        unreachable.afterPropertiesSet();
        unreachable.start();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        EventStreamPublisher publisher = new EventStreamPublisher(
                new ReactiveRedisMessaging(new ReactiveStringRedisTemplate(unreachable)), clock, meters);
        try {
            publisher
                    .publishTransient("ses_x", null, null, new StreamEvent.EventDelta("b1", BlockKind.TEXT, "x"))
                    .block(Duration.ofSeconds(10));

            assertThat(meters.get("meridian.event.publish.failures").counter().count())
                    .isEqualTo(1.0);
        } finally {
            unreachable.destroy();
        }
    }
}
