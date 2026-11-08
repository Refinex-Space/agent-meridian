package cn.refinex.meridian.engine.agentscope.testkit.model;

import cn.refinex.meridian.common.error.NotFoundException;
import cn.refinex.meridian.common.id.Ids;
import cn.refinex.meridian.engine.spi.error.BudgetExceededException;
import cn.refinex.meridian.engine.spi.error.RateLimitTimeoutException;
import cn.refinex.meridian.engine.spi.port.ModelGateway;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import reactor.core.publisher.Mono;

/**
 * {@link ModelGateway} 的内存实现：登记的路由、一个总的 token 预算、可切换的限流超时，并记录每次调用，供 {@code RoutedModel}
 * 与压缩守护的测试断言（架构设计「模型调用」）。
 *
 * <p>预算只有一级，用来验证"预扣、结算、释放成对出现"与"预算不足不重试、不切换"；四级预算与 Redis 计数由 runtime 的实现测试。
 *
 * <p>线程安全：可被并发调用。
 */
public final class InMemoryModelGateway implements ModelGateway {

    private final Map<String, ModelRoute> routes = new ConcurrentHashMap<>();
    private final Map<String, Long> reserved = new ConcurrentHashMap<>();
    private final List<ModelCallRecord> settlements = new CopyOnWriteArrayList<>();
    private final List<String> releases = new CopyOnWriteArrayList<>();
    private final List<Integer> rateLimitRequests = new CopyOnWriteArrayList<>();
    private final List<Integer> prechecks = new CopyOnWriteArrayList<>();
    private final AtomicInteger reservations = new AtomicInteger();
    private volatile Long budgetLimit;
    private volatile boolean rateLimitTimeout;
    private long used;

    /**
     * 登记一个 OpenAI 兼容的路由。
     *
     * @param modelId 模型 ID
     * @param upstreamModel 供应商侧模型名，测试用的供应商工厂按它找底层模型
     * @param contextWindow 上下文窗口
     * @param maxOutputTokens 默认最大输出
     * @return 本网关
     */
    public InMemoryModelGateway route(String modelId, String upstreamModel, int contextWindow, int maxOutputTokens) {
        routes.put(
                modelId,
                new ModelRoute(
                        modelId,
                        "prv_test",
                        Provider.OPENAI_COMPATIBLE,
                        null,
                        upstreamModel,
                        null,
                        contextWindow,
                        maxOutputTokens));
        return this;
    }

    /**
     * 设置预算上限（token）；为空表示不限。
     *
     * @param limit 上限
     * @return 本网关
     */
    public InMemoryModelGateway budgetLimit(Long limit) {
        this.budgetLimit = limit;
        return this;
    }

    /**
     * 让限流等待一律超时。
     *
     * @param timeout 是否超时
     * @return 本网关
     */
    public InMemoryModelGateway rateLimitTimeout(boolean timeout) {
        this.rateLimitTimeout = timeout;
        return this;
    }

    @Override
    public Mono<ModelDescriptor> describe(String modelId) {
        return route(modelId).map(r -> new ModelDescriptor(modelId, r.contextWindow(), r.maxOutputTokens()));
    }

    @Override
    public Mono<ModelRoute> resolve(String modelId) {
        return route(modelId);
    }

    @Override
    public Mono<Void> awaitRateLimit(ModelRoute route, int estimatedTokens) {
        return Mono.defer(() -> {
            rateLimitRequests.add(estimatedTokens);
            return rateLimitTimeout ? Mono.error(new RateLimitTimeoutException("限流等待超时（测试）")) : Mono.empty();
        });
    }

    @Override
    public Mono<Void> precheck(ModelRoute route, int estimatedTokens) {
        return Mono.defer(() -> {
            prechecks.add(estimatedTokens);
            if (rateLimitTimeout) {
                return Mono.error(new RateLimitTimeoutException("限流等待超时（测试）"));
            }
            synchronized (this) {
                if (budgetLimit != null && used + estimatedTokens > budgetLimit) {
                    return Mono.error(new BudgetExceededException("预算不足（测试）"));
                }
            }
            return Mono.empty();
        });
    }

    @Override
    public Mono<BudgetReservation> reserve(ModelRoute route, int estimatedTokens) {
        return Mono.defer(() -> {
            synchronized (this) {
                if (budgetLimit != null && used + estimatedTokens > budgetLimit) {
                    return Mono.error(new BudgetExceededException("预算不足（测试）"));
                }
                used += estimatedTokens;
            }
            reservations.incrementAndGet();
            String id = Ids.newUlid();
            reserved.put(id, (long) estimatedTokens);
            return Mono.just(new BudgetReservation(id, estimatedTokens));
        });
    }

    @Override
    public Mono<Void> settle(BudgetReservation reservation, ModelCallRecord record) {
        return Mono.fromRunnable(() -> {
            Long amount = reserved.remove(reservation.reservationId());
            if (amount == null) {
                throw new IllegalStateException("重复结算：" + reservation.reservationId());
            }
            synchronized (this) {
                used += record.usage().totalTokens() - amount;
            }
            settlements.add(record);
        });
    }

    @Override
    public Mono<Void> release(BudgetReservation reservation) {
        return Mono.fromRunnable(() -> {
            Long amount = reserved.remove(reservation.reservationId());
            if (amount == null) {
                throw new IllegalStateException("重复释放：" + reservation.reservationId());
            }
            synchronized (this) {
                used -= amount;
            }
            releases.add(reservation.reservationId());
        });
    }

    /**
     * 结算过的调用，按结算顺序。
     *
     * @return 计量信息
     */
    public List<ModelCallRecord> settlements() {
        return List.copyOf(settlements);
    }

    /**
     * 释放过的预扣数。
     *
     * @return 次数
     */
    public int releases() {
        return releases.size();
    }

    /**
     * 预扣次数。
     *
     * @return 次数
     */
    public int reservations() {
        return reservations.get();
    }

    /**
     * 还没有结算或释放的预扣数。
     *
     * @return 个数
     */
    public int outstanding() {
        return reserved.size();
    }

    /**
     * 限流等待的请求，每次的估算 token。
     *
     * @return 估算值，按调用顺序
     */
    public List<Integer> rateLimitRequests() {
        return List.copyOf(rateLimitRequests);
    }

    /**
     * 预检请求，每次的估算 token。
     *
     * @return 估算值，按调用顺序
     */
    public List<Integer> prechecks() {
        return List.copyOf(prechecks);
    }

    /**
     * 当前计入预算的 token（含未结算的预扣）。
     *
     * @return token 数
     */
    public synchronized long used() {
        return used;
    }

    private Mono<ModelRoute> route(String modelId) {
        ModelRoute route = routes.get(modelId);
        return route == null ? Mono.error(new NotFoundException("模型不存在：" + modelId)) : Mono.just(route);
    }
}
