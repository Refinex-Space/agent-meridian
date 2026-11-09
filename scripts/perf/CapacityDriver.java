import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * runtime 的容量与内存压测驱动（R1：P7 容量、P11 内存），只用 JDK，经公开 API（REST + SSE）驱动一个运行中的 runtime。
 *
 * <p>三种模式：
 *
 * <ul>
 *   <li>{@code turns}：{@code --sessions} 个会话各自闭环执行轮次（发消息 → 经 SSE 等 {@code session.status_idle} → 下一轮），
 *       模型由脚本化模型服务按 {@code --scripts} 随机选择的脚本模拟延迟与流式输出。统计吞吐、平均并发轮次、
 *       从发送到 {@code session.status_running} 的耗时、整轮耗时与事件推送延迟。
 *   <li>{@code sse}：{@code --sessions} 个会话按 {@code turns} 的方式持续产生事件，另按 {@code --ramp} 每秒逐步打开
 *       {@code --connections} 个带增量（{@code event_deltas=true}）的 SSE 连接，均匀分到这些会话上，统计建连成功数、
 *       每秒收到的事件数与推送延迟。
 *   <li>{@code rotate}：{@code --threads} 个新会话轮转执行（每个会话一轮短对话后不再使用），并发 {@code --concurrency}；
 *       每完成 {@code --checkpoint} 个，对 {@code --runtime-pid} 执行 {@code jcmd GC.class_histogram}（触发一次 Full GC），
 *       记录存活对象总量与 AgentScope 状态缓存相关类的实例数。
 * </ul>
 *
 * <p>运行：{@code scripts/perf/capacity.sh <模式> [参数]}。API Key 取环境变量 {@code MERIDIAN_DEV_API_KEY}，不打印。
 * 推送延迟 = 收到时间 − 事件信封的 {@code created_at}，驱动与 runtime 需在同一台机器上（同一时钟）。
 */
public final class CapacityDriver {

    private static final Pattern ID = Pattern.compile("\"id\"\\s*:\\s*\"(ses_[A-Za-z0-9_]+)\"");
    private static final Pattern TYPE = Pattern.compile("\"type\"\\s*:\\s*\"([a-z_.]+)\"");
    private static final Pattern CREATED_AT = Pattern.compile("\"created_at\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern VALUE = Pattern.compile("\"value\"\\s*:\\s*([0-9.Ee+-]+)");
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final Map<String, String> options;
    private final URI base;
    private final String apiKey;
    private final HttpClient http;
    private final ExecutorService virtualThreads = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicBoolean measuring = new AtomicBoolean(false);
    private final AtomicBoolean stopping = new AtomicBoolean(false);
    private volatile long windowStart = Long.MAX_VALUE;
    private volatile long windowEnd = Long.MIN_VALUE;

    private final Histogram eventLatency = new Histogram();
    private final Histogram timeToRunning = new Histogram();
    private final Histogram turnDuration = new Histogram();
    private final LongAdder eventsReceived = new LongAdder();
    private final LongAdder turnsCompleted = new LongAdder();
    private final LongAdder turnFailures = new LongAdder();
    private final LongAdder turnBusyMillis = new LongAdder();
    private final LongAdder httpErrors = new LongAdder();
    private final AtomicInteger openStreams = new AtomicInteger();
    private final LongAdder streamFailures = new LongAdder();
    private final ConcurrentLinkedQueue<String> errorSamples = new ConcurrentLinkedQueue<>();
    private final List<String> samples = new ArrayList<>();

    private CapacityDriver(Map<String, String> options) {
        this.options = options;
        this.base = URI.create(option("base-url", "http://127.0.0.1:18080"));
        this.apiKey = System.getenv("MERIDIAN_DEV_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("缺少环境变量 MERIDIAN_DEV_API_KEY");
        }
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .executor(virtualThreads)
                .build();
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("用法：CapacityDriver turns|sse|rotate [--key=value ...]");
            System.exit(2);
        }
        Map<String, String> options = new HashMap<>();
        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            int eq = arg.indexOf('=');
            if (!arg.startsWith("--") || eq < 0) {
                throw new IllegalArgumentException("参数格式为 --key=value：" + arg);
            }
            options.put(arg.substring(2, eq), arg.substring(eq + 1));
        }
        CapacityDriver driver = new CapacityDriver(options);
        switch (args[0]) {
            case "turns" -> driver.turns();
            case "sse" -> driver.sse();
            case "rotate" -> driver.rotate();
            default -> throw new IllegalArgumentException("未知模式：" + args[0]);
        }
        System.exit(0);
    }

    // ---------------------------------------------------------------- turns

    private void turns() throws Exception {
        int sessions = intOption("sessions", 50);
        Duration warmup = durationOption("warmup", "20s");
        Duration duration = durationOption("duration", "60s");
        log("turns：会话 %d，预热 %s，测量 %s，脚本 %s", sessions, warmup, duration, scripts());
        List<Session> active = startProducers(sessions, Duration.ofMillis(longOption("ramp-ms", 20)));
        runMeasurement(warmup, duration, () -> String.format(
                "open_streams=%d turns=%d failures=%d", openStreams.get(), turnsCompleted.sum(), turnFailures.sum()));
        stopAll(active);
        double seconds = duration.toMillis() / 1000.0;
        report("turns", Map.of(
                "会话数（闭环）", String.valueOf(sessions),
                "完成轮次", String.valueOf(turnsCompleted.sum()),
                "失败轮次", String.valueOf(turnFailures.sum()),
                "吞吐（轮/秒）", fmt(turnsCompleted.sum() / seconds),
                "平均并发执行的轮次", fmt(turnBusyMillis.sum() / (double) duration.toMillis()),
                "发送到 status_running（毫秒）", timeToRunning.summary(),
                "整轮耗时（毫秒）", turnDuration.summary(),
                "事件推送延迟（毫秒）", eventLatency.summary(),
                "HTTP 错误", String.valueOf(httpErrors.sum())));
    }

    // ---------------------------------------------------------------- sse

    private void sse() throws Exception {
        int producers = intOption("sessions", 100);
        int connections = intOption("connections", 1000);
        int ramp = intOption("ramp", 200);
        Duration warmup = durationOption("warmup", "15s");
        Duration duration = durationOption("duration", "60s");
        log("sse：产生事件的会话 %d，连接 %d（每秒 %d 个），测量 %s", producers, connections, ramp, duration);
        List<Session> active = startProducers(producers, Duration.ofMillis(20));
        AtomicInteger listenersOpen = new AtomicInteger();
        List<Stream> listeners = new ArrayList<>();
        long intervalNanos = TimeUnit.SECONDS.toNanos(1) / Math.max(1, ramp);
        long next = System.nanoTime();
        for (int i = 0; i < connections; i++) {
            Session target = active.get(i % active.size());
            Stream stream = new Stream(target.id, true, null, listenersOpen);
            listeners.add(stream);
            virtualThreads.submit(stream);
            next += intervalNanos;
            long wait = next - System.nanoTime();
            if (wait > 0) {
                TimeUnit.NANOSECONDS.sleep(wait);
            }
            if ((i + 1) % Math.max(1, ramp * 5) == 0) {
                log("已发起 %d 个监听连接，已建立 %d，失败 %d", i + 1, listenersOpen.get(), streamFailures.sum());
            }
        }
        runMeasurement(warmup, duration, () -> String.format(
                "listeners_open=%d stream_failures=%d events=%d",
                listenersOpen.get(), streamFailures.sum(), eventsReceived.sum()));
        int established = listenersOpen.get();
        listeners.forEach(Stream::close);
        stopAll(active);
        double seconds = duration.toMillis() / 1000.0;
        report("sse", Map.of(
                "产生事件的会话", String.valueOf(producers),
                "目标连接数", String.valueOf(connections),
                "测量结束时保持的连接数", String.valueOf(established),
                "连接失败或中断", String.valueOf(streamFailures.sum()),
                "收到事件（含增量，每秒）", fmt(eventsReceived.sum() / seconds),
                "事件推送延迟（毫秒）", eventLatency.summary(),
                "生产者完成轮次", String.valueOf(turnsCompleted.sum()),
                "HTTP 错误", String.valueOf(httpErrors.sum())));
    }

    // ---------------------------------------------------------------- rotate

    private void rotate() throws Exception {
        int threads = intOption("threads", 100_000);
        int concurrency = intOption("concurrency", 50);
        int checkpoint = intOption("checkpoint", 10_000);
        long pid = longOption("runtime-pid", -1);
        if (pid <= 0) {
            throw new IllegalArgumentException("rotate 需要 --runtime-pid");
        }
        log("rotate：线程 %d，并发 %d，每 %d 个记录一次直方图", threads, concurrency, checkpoint);
        histogram(pid, 0);
        AtomicInteger issued = new AtomicInteger();
        AtomicInteger done = new AtomicInteger();
        Object checkpointLock = new Object();
        List<java.util.concurrent.Future<?>> workers = new ArrayList<>();
        long started = System.nanoTime();
        windowStart = started;
        windowEnd = Long.MAX_VALUE;
        measuring.set(true);
        for (int w = 0; w < concurrency; w++) {
            workers.add(virtualThreads.submit(() -> {
                while (issued.incrementAndGet() <= threads) {
                    try {
                        oneShotThread();
                    } catch (Exception e) {
                        turnFailures.increment();
                        sampleError("rotate: " + e);
                    }
                    int finished = done.incrementAndGet();
                    if (finished % 1000 == 0) {
                        log("已完成 %d 个线程，失败 %d，用时 %d 秒",
                                finished, turnFailures.sum(), elapsedSeconds(started));
                    }
                    if (finished % checkpoint == 0) {
                        synchronized (checkpointLock) {
                            histogram(pid, finished);
                        }
                    }
                }
                return null;
            }));
        }
        for (var worker : workers) {
            worker.get();
        }
        log("全部完成，空闲 30 秒后再记录一次");
        Thread.sleep(30_000);
        histogram(pid, -1);
        report("rotate", Map.of(
                "线程数", String.valueOf(threads),
                "并发", String.valueOf(concurrency),
                "失败", String.valueOf(turnFailures.sum()),
                "用时（秒）", String.valueOf(elapsedSeconds(started)),
                "整轮耗时（毫秒）", turnDuration.summary()));
    }

    /** 新建一个会话，执行一轮短对话后关闭事件流（会话留在库中，不再使用）。 */
    private void oneShotThread() throws Exception {
        String sessionId = createSession();
        LinkedBlockingQueue<String> events = new LinkedBlockingQueue<>();
        Stream stream = new Stream(sessionId, false, events, new AtomicInteger());
        virtualThreads.submit(stream);
        try {
            stream.awaitOpen();
            long t0 = System.nanoTime();
            sendMessage(sessionId, "#script:chat 你好");
            awaitTurn(events, t0);
        } finally {
            stream.close();
        }
    }

    // ---------------------------------------------------------------- 生产者

    private List<Session> startProducers(int count, Duration spacing) throws Exception {
        List<Session> sessions = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Session session = new Session(createSession());
            sessions.add(session);
            virtualThreads.submit(session);
            Thread.sleep(spacing.toMillis());
        }
        return sessions;
    }

    private void stopAll(List<Session> sessions) throws InterruptedException {
        stopping.set(true);
        measuring.set(false);
        log("停止生产者，等待进行中的轮次结束");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        for (Session session : sessions) {
            session.awaitStopped(Math.max(0, deadline - System.nanoTime()));
        }
    }

    /** 一个闭环会话：发消息、等本轮结束，再发下一条。 */
    private final class Session implements Runnable {
        private final String id;
        private final java.util.concurrent.CountDownLatch stopped = new java.util.concurrent.CountDownLatch(1);

        Session(String id) {
            this.id = id;
        }

        @Override
        public void run() {
            LinkedBlockingQueue<String> events = new LinkedBlockingQueue<>();
            Stream stream = new Stream(id, false, events, openStreams);
            virtualThreads.submit(stream);
            try {
                stream.awaitOpen();
                List<String> scripts = scripts();
                int round = 0;
                while (!stopping.get()) {
                    String script = scripts.get(ThreadLocalRandom.current().nextInt(scripts.size()));
                    long t0 = System.nanoTime();
                    sendMessage(id, "#script:" + script + " 第 " + (++round) + " 轮");
                    boolean ok = awaitTurn(events, t0);
                    long t1 = System.nanoTime();
                    if (measuring.get()) {
                        if (ok) {
                            turnsCompleted.increment();
                        } else {
                            turnFailures.increment();
                        }
                    }
                    // 平均并发 = 各轮与测量窗口重叠的时长之和 / 窗口时长
                    long from = Math.max(t0, windowStart);
                    long to = Math.min(t1, windowEnd);
                    if (to > from) {
                        turnBusyMillis.add((to - from) / 1_000_000);
                    }
                }
            } catch (Exception e) {
                if (!stopping.get()) {
                    turnFailures.increment();
                    sampleError("session " + id + ": " + e);
                }
            } finally {
                stream.close();
                stopped.countDown();
            }
        }

        void awaitStopped(long nanos) throws InterruptedException {
            stopped.await(nanos, TimeUnit.NANOSECONDS);
        }
    }

    /**
     * 等待本轮结束：记录发送到 {@code session.status_running} 的耗时与整轮耗时；以 {@code session.status_idle} 结束为成功，
     * {@code session.error}、{@code requires_action}、{@code terminated} 或 120 秒超时为失败。
     */
    private boolean awaitTurn(LinkedBlockingQueue<String> events, long t0) throws InterruptedException {
        boolean running = false;
        long deadline = t0 + TimeUnit.SECONDS.toNanos(120);
        while (true) {
            String type = events.poll(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            if (type == null) {
                sampleError("轮次超时");
                return false;
            }
            long elapsedMillis = (System.nanoTime() - t0) / 1_000_000;
            switch (type) {
                case "session.status_running" -> {
                    if (!running && measuring.get() && t0 >= windowStart) {
                        timeToRunning.record(elapsedMillis);
                    }
                    running = true;
                }
                case "session.status_idle" -> {
                    if (running) {
                        if (measuring.get() && t0 >= windowStart) {
                            turnDuration.record(elapsedMillis);
                        }
                        return true;
                    }
                }
                case "session.error", "session.status_requires_action", "session.status_terminated" -> {
                    sampleError("轮次以 " + type + " 结束");
                    return false;
                }
                default -> {
                    // 其他事件（消息、用量等）不影响判定
                }
            }
        }
    }

    // ---------------------------------------------------------------- SSE

    /** 一个 SSE 连接：解析事件、记录推送延迟；{@code sink} 不为空时把事件类型交给闭环会话。 */
    private final class Stream implements Runnable {
        private final String sessionId;
        private final boolean deltas;
        private final LinkedBlockingQueue<String> sink;
        private final AtomicInteger openGauge;
        private final java.util.concurrent.CountDownLatch opened = new java.util.concurrent.CountDownLatch(1);
        private volatile InputStream body;
        private volatile boolean closed;

        Stream(String sessionId, boolean deltas, LinkedBlockingQueue<String> sink, AtomicInteger openGauge) {
            this.sessionId = sessionId;
            this.deltas = deltas;
            this.sink = sink;
            this.openGauge = openGauge;
        }

        @Override
        public void run() {
            boolean counted = false;
            try {
                HttpRequest request = HttpRequest.newBuilder(base.resolve(
                                "/v1/sessions/" + sessionId + "/stream" + (deltas ? "?event_deltas=true" : "")))
                        .header("Authorization", "Bearer " + apiKey)
                        .header("Accept", "text/event-stream")
                        .GET()
                        .build();
                HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() != 200) {
                    streamFailures.increment();
                    sampleError("stream HTTP " + response.statusCode());
                    response.body().close();
                    return;
                }
                body = response.body();
                openGauge.incrementAndGet();
                counted = true;
                opened.countDown();
                if (closed) {
                    return;
                }
                try (BufferedReader reader =
                        new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("data:")) {
                            onEvent(line.substring(5).trim());
                        }
                    }
                }
                if (!closed) {
                    streamFailures.increment();
                    sampleError("stream 被服务端关闭 " + sessionId);
                }
            } catch (IOException e) {
                if (!closed) {
                    streamFailures.increment();
                    sampleError("stream " + e);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                if (counted) {
                    openGauge.decrementAndGet();
                }
                opened.countDown();
            }
        }

        private void onEvent(String json) {
            Matcher type = TYPE.matcher(json);
            if (!type.find()) {
                return;
            }
            String eventType = type.group(1);
            if (eventType.equals("ping")) {
                return;
            }
            if (measuring.get()) {
                eventsReceived.increment();
                Matcher created = CREATED_AT.matcher(json);
                if (created.find()) {
                    long latency = Duration.between(Instant.parse(created.group(1)), Instant.now()).toMillis();
                    eventLatency.record(Math.max(0, latency));
                }
            }
            if (sink != null) {
                sink.add(eventType);
            }
        }

        void awaitOpen() throws InterruptedException, IOException {
            if (!opened.await(30, TimeUnit.SECONDS) || body == null) {
                throw new IOException("事件流未能建立 " + sessionId);
            }
        }

        void close() {
            closed = true;
            InputStream current = body;
            if (current != null) {
                try {
                    current.close();
                } catch (IOException ignored) {
                    // 关闭时的错误不影响统计
                }
            }
        }
    }

    // ---------------------------------------------------------------- API

    private String createSession() throws IOException, InterruptedException {
        String body = String.format(
                "{\"agent\":\"%s\",\"environment\":\"%s\"}",
                option("agent", "agv_dev_scripted_1"), option("environment", "env_dev_none"));
        String response = post("/v1/sessions", body);
        Matcher matcher = ID.matcher(response);
        if (!matcher.find()) {
            throw new IOException("创建会话的响应中没有 ID：" + truncate(response));
        }
        return matcher.group(1);
    }

    private void sendMessage(String sessionId, String text) throws IOException, InterruptedException {
        post("/v1/sessions/" + sessionId + "/events",
                "{\"events\":[{\"type\":\"user.message\",\"content\":[{\"type\":\"text\",\"text\":\"" + text + "\"}]}]}");
    }

    private String post(String path, String json) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(base.resolve(path))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            httpErrors.increment();
            throw new IOException("POST " + path + " → " + response.statusCode() + " " + truncate(response.body()));
        }
        return response.body();
    }

    // ---------------------------------------------------------------- 采样

    /** 预热后清零统计，测量期间每 5 秒采样一次 runtime 的资源（{@code --runtime-pid} 给出时）。 */
    private void runMeasurement(Duration warmup, Duration duration, java.util.function.Supplier<String> progress)
            throws Exception {
        log("预热 %s", warmup);
        Thread.sleep(warmup.toMillis());
        long end = System.nanoTime() + duration.toNanos();
        windowStart = System.nanoTime();
        windowEnd = end;
        measuring.set(true);
        log("开始测量 %s", duration);
        while (System.nanoTime() < end) {
            Thread.sleep(Math.min(5000, Math.max(1, (end - System.nanoTime()) / 1_000_000)));
            String sample = sampleRuntime() + " " + progress.get();
            samples.add(sample);
            log("%s", sample);
        }
        measuring.set(false);
    }

    private String sampleRuntime() {
        long pid = longOption("runtime-pid", -1);
        StringBuilder out = new StringBuilder();
        if (pid > 0) {
            String ps = exec(List.of("ps", "-o", "%cpu=,rss=", "-p", String.valueOf(pid))).trim();
            String[] parts = ps.split("\\s+");
            if (parts.length == 2) {
                out.append("cpu%=").append(parts[0]).append(" rss_mb=").append(Long.parseLong(parts[1]) / 1024);
            }
        }
        out.append(metric(" heap_mb", "jvm.memory.used?tag=area:heap", 1.0 / (1024 * 1024)));
        out.append(metric(" db_active", "hikaricp.connections.active", 1));
        out.append(metric(" db_pending", "hikaricp.connections.pending", 1));
        out.append(metric(" sse", "meridian.sse.connections", 1));
        out.append(metric(" threads", "jvm.threads.live", 1));
        out.append(metric(" queue", "meridian.dispatch.queue.depth", 1));
        return out.toString().trim();
    }

    private String metric(String label, String name, double scale) {
        try {
            HttpRequest request = HttpRequest.newBuilder(base.resolve("/actuator/metrics/" + name))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return "";
            }
            Matcher matcher = VALUE.matcher(response.body());
            return matcher.find() ? label + "=" + fmt(Double.parseDouble(matcher.group(1)) * scale) : "";
        } catch (Exception e) {
            return "";
        }
    }

    /** 执行 {@code jcmd GC.class_histogram}（只统计存活对象，会先做一次 Full GC），打印总量与关注的类。 */
    private void histogram(long pid, int completed) {
        String output = exec(List.of("jcmd", String.valueOf(pid), "GC.class_histogram"));
        String total = "";
        Map<String, String> interesting = new HashMap<>();
        for (String line : output.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("Total")) {
                total = trimmed;
            }
            for (String cls : List.of(
                    "io.agentscope.core.state.AgentState",
                    "io.agentscope.core.permission.PermissionEngine",
                    "io.agentscope.harness.agent.HarnessAgent",
                    "io.agentscope.core.ReActAgent",
                    "io.agentscope.core.message.Msg",
                    "io.agentscope.core.agent.RuntimeContext")) {
                if (trimmed.endsWith(" " + cls) || trimmed.contains(" " + cls + " ")) {
                    String[] parts = trimmed.split("\\s+");
                    interesting.put(cls.substring(cls.lastIndexOf('.') + 1), parts[1]);
                }
            }
        }
        String label = completed < 0 ? "空闲后" : "已完成 " + completed;
        String line = String.format("[直方图] %s：%s 实例 %s", label, total, interesting);
        samples.add(line);
        log("%s", line);
    }

    private static String exec(List<String> command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            process.waitFor(120, TimeUnit.SECONDS);
            return output;
        } catch (IOException e) {
            return "";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        }
    }

    // ---------------------------------------------------------------- 输出

    private void report(String mode, Map<String, String> rows) {
        StringBuilder out = new StringBuilder();
        out.append("\n## ").append(mode).append(' ').append(options).append('\n');
        rows.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> out.append("- ").append(e.getKey()).append("：").append(e.getValue()).append('\n'));
        out.append("- 采样：\n");
        samples.forEach(s -> out.append("  - ").append(s).append('\n'));
        if (!errorSamples.isEmpty()) {
            out.append("- 错误样本：\n");
            errorSamples.stream().limit(10).forEach(e -> out.append("  - ").append(e).append('\n'));
        }
        System.out.println(out);
    }

    private void sampleError(String message) {
        if (errorSamples.size() < 50) {
            errorSamples.add(truncate(message));
        }
    }

    private static void log(String format, Object... args) {
        System.out.println(LocalTime.now().format(CLOCK) + " " + String.format(format, args));
    }

    private List<String> scripts() {
        return List.of(option("scripts", "cap-2,cap-3,cap-4,cap-5").split(","));
    }

    private String option(String key, String fallback) {
        return options.getOrDefault(key, fallback);
    }

    private int intOption(String key, int fallback) {
        return Integer.parseInt(option(key, String.valueOf(fallback)));
    }

    private long longOption(String key, long fallback) {
        return Long.parseLong(option(key, String.valueOf(fallback)));
    }

    private Duration durationOption(String key, String fallback) {
        String value = option(key, fallback);
        return Duration.parse("PT" + value.toUpperCase());
    }

    private static long elapsedSeconds(long startedNanos) {
        return TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startedNanos);
    }

    private static String fmt(double value) {
        return String.format("%.1f", value);
    }

    private static String truncate(String text) {
        return text == null ? "" : text.length() > 300 ? text.substring(0, 300) + "…" : text;
    }

    /** 毫秒精度的直方图（0 至 120000 毫秒，超出的记在最后一格）。 */
    private static final class Histogram {
        private static final int MAX = 120_000;
        private final AtomicLongArray counts = new AtomicLongArray(MAX + 1);
        private final LongAdder total = new LongAdder();

        void record(long millis) {
            counts.incrementAndGet((int) Math.min(MAX, Math.max(0, millis)));
            total.increment();
        }

        String summary() {
            long n = total.sum();
            if (n == 0) {
                return "无样本";
            }
            return String.format("n=%d p50=%d p95=%d p99=%d max=%d",
                    n, percentile(0.50, n), percentile(0.95, n), percentile(0.99, n), percentile(1.0, n));
        }

        private long percentile(double q, long n) {
            long rank = Math.max(1, (long) Math.ceil(q * n));
            long seen = 0;
            for (int i = 0; i <= MAX; i++) {
                seen += counts.get(i);
                if (seen >= rank) {
                    return i;
                }
            }
            return MAX;
        }
    }
}
