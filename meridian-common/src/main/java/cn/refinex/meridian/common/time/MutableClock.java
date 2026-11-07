package cn.refinex.meridian.common.time;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 可控时钟：测试中替代系统时钟，显式推进时间，用于验证租约过期、超时、保留期等时序逻辑，
 * 而不依赖 {@code Thread.sleep}（测试规范「测试的写法」）。
 *
 * <p>线程安全：时间点用原子引用保存，可以在被测代码运行时从测试线程推进。
 */
public final class MutableClock extends Clock {

    private final AtomicReference<Instant> now;
    private final ZoneId zone;

    private MutableClock(AtomicReference<Instant> now, ZoneId zone) {
        this.now = now;
        this.zone = zone;
    }

    /**
     * 创建一个停在指定时间点的时钟，时区为 UTC。
     *
     * @param start 初始时间点
     * @return 可控时钟
     */
    public static MutableClock at(Instant start) {
        return new MutableClock(new AtomicReference<>(start), ZoneOffset.UTC);
    }

    /**
     * 把时间向前推进。
     *
     * @param duration 推进的时长，不能为负
     * @return 推进后的时间点
     */
    public Instant advance(Duration duration) {
        if (duration.isNegative()) {
            throw new IllegalArgumentException("时钟只能向前推进：" + duration);
        }
        return now.updateAndGet(current -> current.plus(duration));
    }

    /**
     * 把时间设置为指定时间点，可以回拨，用于模拟时钟漂移。
     *
     * @param instant 新的时间点
     */
    public void set(Instant instant) {
        now.set(instant);
    }

    @Override
    public Instant instant() {
        return now.get();
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    /** 返回共享同一时间点、时区不同的时钟：推进任一个，另一个同步变化。 */
    @Override
    public Clock withZone(ZoneId newZone) {
        return new MutableClock(now, newZone);
    }
}
