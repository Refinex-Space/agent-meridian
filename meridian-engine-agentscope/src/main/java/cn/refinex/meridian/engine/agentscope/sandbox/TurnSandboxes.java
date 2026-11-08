package cn.refinex.meridian.engine.agentscope.sandbox;

import cn.refinex.meridian.common.context.PlatformContext;
import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.common.error.MeridianException;
import cn.refinex.meridian.common.error.NotFoundException;
import cn.refinex.meridian.engine.agentscope.fs.SandboxWorkspaceFilesystem;
import cn.refinex.meridian.engine.spi.driver.SandboxHandle;
import cn.refinex.meridian.engine.spi.error.SandboxUnavailableException;
import cn.refinex.meridian.engine.spi.port.SandboxStatePort;
import io.agentscope.extensions.sandbox.kubernetes.KubernetesFilesystemSpec;
import io.agentscope.harness.agent.filesystem.spec.SandboxFilesystemSpec;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxContext;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code sandbox} 档每一轮的外部沙箱：经 {@link SandboxStatePort} 读出 runtime 已为本会话准备好的沙箱，经
 * {@link SandboxAttachment} 取得已启动的沙箱，包成 {@code SandboxContext.builder().externalSandbox(...)}（架构设计「会话沙箱」
 * "获取"）。harness 对外部沙箱不停止也不持久化，沙箱因此跨轮次存活；同一会话的主线程与子线程拿到的是同一个沙箱。
 *
 * <p>沙箱何时创建、何时回收由 runtime 的 {@code SandboxService} 决定：它在轮次开始前确保会话有沙箱并落库，这里只读取。
 * 读不到或沙箱已不存在时以 {@link SandboxUnavailableException}（可重试）结束本轮，下一次执行时 runtime 重新准备（从快照恢复）。
 *
 * <p>线程安全：无状态。方法会阻塞，在虚拟线程上调用。
 */
public final class TurnSandboxes {

    /** 读取沙箱状态的超时。 */
    static final Duration STATE_READ_TIMEOUT = Duration.ofSeconds(30);

    private final SandboxStatePort states;
    private final SandboxAttachment attachment;

    /**
     * 创建每轮沙箱的提供者。
     *
     * @param states 会话沙箱状态端口
     * @param attachment 按状态取得沙箱
     */
    public TurnSandboxes(SandboxStatePort states, SandboxAttachment attachment) {
        this.states = Objects.requireNonNull(states, "states");
        this.attachment = Objects.requireNonNull(attachment, "attachment");
    }

    /**
     * 装配 {@code sandbox} 档智能体用的文件系统规范：{@code KubernetesFilesystemSpec} 只描述工作区根与执行服务端口，客户端是
     * {@link PlatformManagedSandboxClient}，harness 不能自己创建沙箱。每次装配一个新实例（装配器会关闭工作区投影）。
     *
     * @param settings 驱动配置，取命名空间与工作区根
     * @return 文件系统规范
     */
    public static SandboxFilesystemSpec filesystemSpec(SandboxDriverSettings settings) {
        if (!SandboxWorkspaceFilesystem.WORKSPACE_ROOT.equals(settings.workspaceRoot())) {
            throw new IllegalArgumentException(
                    "沙箱工作区根由镜像固定为 " + SandboxWorkspaceFilesystem.WORKSPACE_ROOT + "，配置为 " + settings.workspaceRoot());
        }
        return new KubernetesFilesystemSpec()
                .client(PlatformManagedSandboxClient.INSTANCE)
                .namespace(settings.namespace())
                .workspaceRoot(settings.workspaceRoot())
                .fileApiBaseDir(settings.workspaceRoot())
                .serverPort(KubernetesSandboxDriver.SERVER_PORT);
    }

    /**
     * 本轮的外部沙箱上下文。
     *
     * @param platform 本轮的平台上下文，必须有会话
     * @return 只含外部沙箱的 {@code SandboxContext}
     * @throws SandboxUnavailableException 会话没有沙箱、沙箱已不存在或连接失败时
     */
    public SandboxContext externalContext(PlatformContext platform) {
        return SandboxContext.builder().externalSandbox(sandbox(platform)).build();
    }

    /**
     * 本轮的会话沙箱。
     *
     * @param platform 本轮的平台上下文，必须有会话
     * @return 已启动的沙箱
     * @throws SandboxUnavailableException 会话没有沙箱、沙箱已不存在或连接失败时
     */
    public Sandbox sandbox(PlatformContext platform) {
        String sessionId = Objects.requireNonNull(platform.sessionId(), "sessionId");
        Optional<SandboxHandle> handle = states.find(sessionId)
                .contextWrite(PlatformContexts.write(platform))
                .blockOptional(STATE_READ_TIMEOUT)
                .flatMap(found -> found);
        if (handle.isEmpty()) {
            throw new SandboxUnavailableException("会话还没有可用的沙箱：" + sessionId, null);
        }
        try {
            return attachment.attach(handle.get().state());
        } catch (NotFoundException e) {
            throw new SandboxUnavailableException("会话沙箱已不存在，下一次执行时恢复：" + sessionId, e);
        } catch (MeridianException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new SandboxUnavailableException("连接会话沙箱失败：" + sessionId, e);
        }
    }
}
