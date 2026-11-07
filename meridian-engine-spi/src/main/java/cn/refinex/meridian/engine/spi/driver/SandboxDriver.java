package cn.refinex.meridian.engine.spi.driver;

import cn.refinex.meridian.engine.spi.spec.EnvironmentSpec.SandboxSize;
import java.util.Objects;
import reactor.core.publisher.Mono;

/**
 * 沙箱的创建、恢复、快照、删除，内部使用 AgentScope Kubernetes 扩展的 {@code SandboxClient}，由
 * {@code meridian-engine-agentscope} 的 {@code sandbox} 包实现，见架构设计「端口」「会话沙箱」。
 *
 * <p>何时创建、何时回收、状态存在哪里由 runtime 的 {@code SandboxService} 决定；驱动只执行具体操作，所有操作都是
 * 阻塞的远程调用，由实现调度到虚拟线程。
 */
public interface SandboxDriver {

    /**
     * 从对应规格的预热池认领一个新沙箱。
     *
     * @param request 创建请求
     * @return 新沙箱的句柄
     */
    Mono<SandboxHandle> create(SandboxRequest request);

    /**
     * 按序列化状态重新连接一个仍在运行的沙箱。
     *
     * @param state 序列化后的沙箱状态
     * @return 沙箱句柄；沙箱已不存在时以 {@code NotFoundException} 失败，调用方改为从快照恢复
     */
    Mono<SandboxHandle> resume(String state);

    /**
     * 新建沙箱并从快照恢复工作区，用于空闲回收之后的下一轮。
     *
     * @param request 创建请求
     * @param snapshotKey 快照在对象存储中的键
     * @return 新沙箱的句柄
     */
    Mono<SandboxHandle> restore(SandboxRequest request, String snapshotKey);

    /**
     * 把沙箱的工作区打成快照存入对象存储，空闲回收前调用。
     *
     * @param handle 沙箱句柄
     * @return 快照在对象存储中的键
     */
    Mono<String> snapshot(SandboxHandle handle);

    /**
     * 删除沙箱。沙箱已不存在时无副作用。
     *
     * @param handle 沙箱句柄
     * @return 删除后完成
     */
    Mono<Void> delete(SandboxHandle handle);

    /**
     * 创建沙箱的请求。
     *
     * @param sessionId 会话 ID，记入沙箱标签便于排查；必填
     * @param size 资源规格，决定使用哪个模板与预热池；必填
     */
    record SandboxRequest(String sessionId, SandboxSize size) {

        /**
         * 创建请求。
         *
         * @throws NullPointerException 缺少任一字段时
         */
        public SandboxRequest {
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(size, "size");
        }
    }
}
