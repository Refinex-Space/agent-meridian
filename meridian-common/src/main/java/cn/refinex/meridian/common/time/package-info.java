/**
 * 时钟约定：业务代码通过注入的 {@link java.time.Clock} 取时间，禁止直接调用 {@code Instant.now()}
 * （编码规范「通用能力复用」）。生产环境注入 {@code Clock.systemUTC()}，测试注入
 * {@link cn.refinex.meridian.common.time.MutableClock}。
 *
 * <p>数据库中的时间（如 {@code now()}）与 Java 侧时钟不是同一来源：需要两者比较的逻辑，统一以一侧为准。
 */
package cn.refinex.meridian.common.time;
