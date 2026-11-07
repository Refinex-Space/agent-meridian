package cn.refinex.meridian.runtime.support;

import cn.refinex.meridian.common.json.JsonMappers;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.Disposable;
import tools.jackson.databind.JsonNode;

/**
 * SSE 测试客户端：订阅会话流或线程流，收集收到的帧，并按规范校验每一帧的 {@code data}（{@link ContractValidator}）。
 */
public final class SseTestClient {

    private static final ParameterizedTypeReference<ServerSentEvent<String>> FRAME =
            new ParameterizedTypeReference<>() {};

    private final WebClient client;
    private final String apiKey;

    /**
     * 创建客户端。
     *
     * @param port runtime 的端口
     * @param apiKey API Key 明文
     */
    public SseTestClient(int port, String apiKey) {
        this.client = WebClient.builder().baseUrl("http://localhost:" + port).build();
        this.apiKey = apiKey;
    }

    /**
     * 打开一个事件流，在后台收集帧。
     *
     * @param pathAndQuery 路径与查询串，例如 {@code /v1/sessions/ses_…/stream?event_deltas=true}
     * @param lastEventId {@code Last-Event-ID}；可为空
     * @return 连接
     */
    public Connection open(String pathAndQuery, String lastEventId) {
        List<Frame> frames = new CopyOnWriteArrayList<>();
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        boolean[] completed = new boolean[1];
        Disposable subscription = client.get()
                .uri(pathAndQuery)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .headers(h -> {
                    if (lastEventId != null) {
                        h.set("Last-Event-ID", lastEventId);
                    }
                })
                .retrieve()
                .bodyToFlux(FRAME)
                .subscribe(
                        event -> {
                            Frame frame = new Frame(event.event(), event.id(), event.data());
                            ContractValidator.validateEvent(frame.data(), frame.id() != null);
                            frames.add(frame);
                        },
                        errors::add,
                        () -> completed[0] = true);
        return new Connection(subscription, frames, errors, completed);
    }

    /**
     * 一个打开的事件流。
     *
     * @param subscription 订阅，关闭即断开连接
     * @param frames 收到的帧
     * @param errors 连接上的错误（包括帧不符合规范）
     * @param completedFlag 服务端是否已关闭连接
     */
    public record Connection(
            Disposable subscription, List<Frame> frames, List<Throwable> errors, boolean[] completedFlag) {

        /** 断开连接。 */
        public void close() {
            subscription.dispose();
        }

        /**
         * 服务端是否已正常关闭连接。
         *
         * @return 已关闭时为 true
         */
        public boolean completed() {
            return completedFlag[0];
        }

        /**
         * 等待直到收到满足条件的帧。
         *
         * @param condition 条件
         * @param timeout 最长等待
         * @return 收到的全部帧
         * @throws AssertionError 超时或连接出错时
         */
        public List<Frame> await(Predicate<List<Frame>> condition, Duration timeout) {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                if (!errors.isEmpty()) {
                    throw new AssertionError("SSE 连接出错", errors.getFirst());
                }
                if (condition.test(List.copyOf(frames))) {
                    return List.copyOf(frames);
                }
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            }
            throw new AssertionError("等待 SSE 帧超时，已收到：" + frames);
        }

        /**
         * 持久化事件的 seq，按收到的顺序。
         *
         * @return seq
         */
        public List<Long> seqs() {
            return frames.stream().filter(f -> f.id() != null).map(Frame::seq).toList();
        }
    }

    /**
     * 收到的一帧。
     *
     * @param event {@code event:}
     * @param id {@code id:}；只在流上的事件为空
     * @param data {@code data:}
     */
    public record Frame(String event, String id, String data) {

        /**
         * 帧的 seq。
         *
         * @return seq
         */
        public long seq() {
            return Long.parseLong(id);
        }

        /**
         * 解析 {@code data}。
         *
         * @return 事件信封
         */
        public JsonNode envelope() {
            return JsonMappers.lenient().readTree(data);
        }
    }
}
