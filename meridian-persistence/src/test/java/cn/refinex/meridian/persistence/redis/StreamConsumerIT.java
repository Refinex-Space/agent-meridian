package cn.refinex.meridian.persistence.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.refinex.meridian.common.time.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

/** T16（ADR-0048 第 7 条、登记事项 D04）：消费组的确认、毒丸进死信、瞬时错误的重投、确认后裁剪。 */
class StreamConsumerIT {

    /** 测试不等待：认领与裁剪每轮都检查，待确认消息立即可以认领。 */
    private static final StreamConsumer.Settings EAGER =
            new StreamConsumer.Settings(10, Duration.ZERO, Duration.ZERO, Duration.ZERO, Duration.ZERO);

    private final RedisMessaging messaging = RedisFixtures.messaging();
    private final MutableClock clock = MutableClock.at(Instant.parse("2026-11-12T08:00:00Z"));
    private String stream;

    @BeforeEach
    void setUp() {
        stream = "stream-consumer-it-" + System.nanoTime();
    }

    @Test
    void handledMessagesAreAcknowledgedAndTrimmed() {
        RecordingHandler handler = new RecordingHandler();
        StreamConsumer consumer = consumer("c1", handler);
        publish("a");
        publish("b");

        assertThat(consumer.pollOnce(Duration.ZERO)).isEqualTo(2);
        assertThat(handler.handled).containsExactly("a", "b");
        // 下一轮裁剪：全部已确认，只剩最后投递的那一条作为边界
        consumer.pollOnce(Duration.ZERO);
        assertThat(RedisFixtures.template().opsForStream().size(stream)).isLessThanOrEqualTo(1);
    }

    @Test
    void aPoisonMessageIsDeadLetteredWithoutBlockingTheRest() {
        RecordingHandler handler = new RecordingHandler();
        handler.poison = "bad";
        StreamConsumer consumer = consumer("c1", handler);
        publish("a");
        publish("bad");
        publish("c");

        assertThat(consumer.pollOnce(Duration.ZERO)).isEqualTo(3);
        assertThat(handler.handled).containsExactly("a", "c");
        assertThat(handler.deadLetters).containsExactly("bad");
        assertThat(messaging.claimIdle(stream, "g", "c2", Duration.ZERO, 10)).isEmpty();
    }

    @Test
    void transientFailuresAreNotAcknowledgedAndAreRedeliveredLater() {
        RecordingHandler handler = new RecordingHandler();
        handler.down = true;
        StreamConsumer first = consumer("c1", handler);
        publish("a");

        assertThatThrownBy(() -> first.pollOnce(Duration.ZERO)).isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(handler.deadLetters).isEmpty();

        // 另一个实例在数据库恢复后认领未确认的消息
        handler.down = false;
        StreamConsumer second = consumer("c2", handler);
        assertThat(second.pollOnce(Duration.ZERO)).isEqualTo(1);
        assertThat(handler.handled).containsExactly("a");
    }

    @Test
    void trimKeepsEntriesThatAreStillPending() {
        messaging.ensureGroup(stream, "g");
        publish("a");
        publish("b");
        List<StreamMessage> read = messaging.readGroup(stream, "g", "c1", 10, Duration.ZERO);
        messaging.ack(stream, "g", List.of(read.get(1).recordId()));

        assertThat(messaging.trimAcknowledged(stream)).isZero();
        assertThat(RedisFixtures.template().opsForStream().size(stream)).isEqualTo(2);

        messaging.ack(stream, "g", List.of(read.get(0).recordId()));
        assertThat(messaging.trimAcknowledged(stream)).isEqualTo(1);
    }

    private StreamConsumer consumer(String name, StreamConsumer.Handler handler) {
        return new StreamConsumer(messaging, stream, "g", name, handler, EAGER, clock);
    }

    private void publish(String value) {
        messaging.publish(stream, Map.of("value", value));
    }

    /** 记录处理与死信；整批中有毒丸或"数据库不可用"时整批失败（模拟事务回滚）。 */
    static final class RecordingHandler implements StreamConsumer.Handler {
        final List<String> handled = new CopyOnWriteArrayList<>();
        final List<String> deadLetters = new ArrayList<>();
        String poison;
        volatile boolean down;

        @Override
        public void handle(List<StreamMessage> messages) {
            if (down) {
                throw new DataAccessResourceFailureException("数据库不可用");
            }
            List<String> values = messages.stream().map(m -> m.field("value")).toList();
            if (values.contains(poison)) {
                throw new IllegalArgumentException("无法解析：" + poison);
            }
            handled.addAll(values);
        }

        @Override
        public boolean deadLetter(StreamMessage message, RuntimeException error) {
            deadLetters.add(message.field("value"));
            return true;
        }
    }
}
