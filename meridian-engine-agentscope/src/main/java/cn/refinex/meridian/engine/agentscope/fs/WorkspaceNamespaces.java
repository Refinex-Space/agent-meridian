package cn.refinex.meridian.engine.agentscope.fs;

import cn.refinex.meridian.common.context.PlatformContext;
import cn.refinex.meridian.engine.spi.port.WorkspaceFiles;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.remote.store.NamespaceFactory;
import java.util.List;

/**
 * 工作区的命名空间：{@code [tenant_id, project_id, end_user_id, session_id]}，四个值都从 {@code RuntimeContext} 中的
 * {@link PlatformContext} 取（架构设计「文件系统装配」）。
 *
 * <p>不能取 {@code RuntimeContext.getSessionId()}：它是线程 ID，用它会让子智能体看不到父线程写的文件。没有终端用户的会话用
 * {@value #NO_END_USER} 占位，与「身份映射」中 {@code userId} 的约定一致。
 */
public final class WorkspaceNamespaces {

    /** 没有终端用户时的占位段。 */
    public static final String NO_END_USER = WorkspaceFiles.NO_END_USER;

    /** 生成命名空间的工厂，每次存储操作都调用。 */
    public static final NamespaceFactory FACTORY = WorkspaceNamespaces::of;

    private WorkspaceNamespaces() {}

    /**
     * 按本次调用的上下文生成命名空间。
     *
     * @param runtimeContext 调用的 {@code RuntimeContext}
     * @return 命名空间
     * @throws IllegalStateException 上下文中没有带会话的 {@code PlatformContext} 时（工作区只能在轮次中访问）
     */
    public static List<String> of(RuntimeContext runtimeContext) {
        PlatformContext platform = runtimeContext == null ? null : runtimeContext.get(PlatformContext.class);
        if (platform == null || platform.projectId() == null || platform.sessionId() == null) {
            throw new IllegalStateException("访问工作区需要 RuntimeContext 中带项目与会话的 PlatformContext");
        }
        return WorkspaceFiles.namespace(
                platform.tenantId(), platform.projectId(), platform.endUserId(), platform.sessionId());
    }
}
