package cn.refinex.meridian.engine.spi.port;

import cn.refinex.meridian.engine.spi.driver.SandboxHandle;
import java.util.Optional;
import reactor.core.publisher.Mono;

/**
 * 读写会话的沙箱状态（序列化后的字符串与 Pod IP）与使用标记，由 runtime 的 {@code sandbox} 实现，列为
 * {@code rt.sessions.sandbox_state}、{@code sandbox_pod_ip}、{@code sandbox_last_used_at}，见架构设计「端口」「会话沙箱」。
 *
 * <p>一个会话一个沙箱，主线程与子智能体共享；沙箱的创建、回收由 runtime 的 {@code SandboxService} 决定，
 * 内核只在执行时读取它并放进 {@code RuntimeContext}。会话从 Reactor Context 中的 {@code PlatformContext} 取。
 */
public interface SandboxStatePort {

    /**
     * 读取会话当前的沙箱。
     *
     * @param sessionId 会话 ID
     * @return 沙箱句柄；会话还没有沙箱或已被回收时为空
     */
    Mono<Optional<SandboxHandle>> find(String sessionId);

    /**
     * 保存会话的沙箱，Pod IP 同时用于出网代理按源 IP 鉴权（架构设计「沙箱出网」）。
     *
     * @param sessionId 会话 ID
     * @param handle 沙箱句柄
     * @return 保存后完成
     */
    Mono<Void> save(String sessionId, SandboxHandle handle);

    /**
     * 更新 {@code sandbox_last_used_at}，空闲回收据此判断。每轮开始时调用。
     *
     * @param sessionId 会话 ID
     * @return 更新后完成
     */
    Mono<Void> markUsed(String sessionId);

    /**
     * 清除会话的沙箱状态与 Pod IP，沙箱删除后调用，保证同 IP 的新 Pod 不继承旧授权。
     *
     * @param sessionId 会话 ID
     * @return 清除后完成
     */
    Mono<Void> clear(String sessionId);
}
