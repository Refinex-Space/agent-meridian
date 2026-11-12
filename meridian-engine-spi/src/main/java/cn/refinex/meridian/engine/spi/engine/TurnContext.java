package cn.refinex.meridian.engine.spi.engine;

import cn.refinex.meridian.common.context.PlatformContext;
import cn.refinex.meridian.engine.spi.spec.CustomToolDeclaration;
import cn.refinex.meridian.engine.spi.spec.PermissionPolicy;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 执行一轮所需的上下文，由 Worker 在认领轮次后构造，见架构设计「内核适配层」「身份映射」「权限判定」。
 *
 * <p>内核据 {@code agentVersionId}、{@code environmentId}、{@code environmentRevision} 取得缓存的智能体实例
 * （未命中时经 {@code AgentConfigPort} 读取配置并装配），把 {@code platform} 以类型键写入
 * {@code RuntimeContext} 与 Reactor Context。AgentScope 的 {@code userId} 由内核按
 * {@code {project_id}:{end_user_id}} 生成，没有终端用户时为 {@code {project_id}:_}。
 *
 * @param platform 平台上下文：租户、项目、会话、线程、轮次、API Key、租约 epoch、预算句柄、值守方式；
 *     其中项目、会话、线程、轮次、租约 epoch、值守方式在本类型中必填
 * @param endUserId 终端用户 ID；可为空，表示会话没有终端用户
 * @param agentVersionId 会话绑定的智能体版本 ID（{@code agv_…}）；必填
 * @param environmentId 会话绑定的环境 ID（{@code env_…}）；必填
 * @param environmentRevision 会话创建时的环境修订号，从 1 开始
 * @param permissions 五层规则合成后的主线程权限策略，每轮开始前安装；必填
 * @param subagentPolicies 每个子智能体预先合成的权限策略：子智能体名 → 策略。按「权限判定」一律为
 *     {@code DONT_ASK} 模式、ask 改写为 deny、allow 是父智能体 allow 的子集；可为空映射
 * @param customTools 本会话声明的自定义工具，每轮注入；可为空列表
 * @param recovery 恢复说明的来源；可为空，表示不是恢复轮
 * @param interruptRequested 认领时本轮已有中断请求（{@code turns.interrupt_requested_at} 不为空）：等待回应时被中断而生成的
 *     恢复轮，或中断请求之后被恢复认领的轮次。内核先把本轮的输入（回应的结论、恢复说明）写进上下文，在第一次推理前按用户中断
 *     结束本轮（{@code stop_reason=interrupted}），见架构设计「消息投递」
 * @param workspaceFiles 本轮输入引用的平台文件已放进工作区的位置：文件 ID（{@code fil_…}）→ 相对工作区根的路径，由 runtime 在调用内核之前
 *     放置后填写（ADR-0050）；内核把引用翻译为这个路径。可为空映射，表示没有放置的文件
 */
public record TurnContext(
        PlatformContext platform,
        String endUserId,
        String agentVersionId,
        String environmentId,
        long environmentRevision,
        PermissionPolicy permissions,
        Map<String, PermissionPolicy> subagentPolicies,
        List<CustomToolDeclaration> customTools,
        RecoveryOrigin recovery,
        boolean interruptRequested,
        Map<String, String> workspaceFiles) {

    /**
     * 创建一轮的上下文。
     *
     * @throws NullPointerException 缺少必填字段时
     * @throws IllegalArgumentException 平台上下文缺少一轮执行必需的字段，或修订号小于 1 时
     */
    public TurnContext {
        Objects.requireNonNull(platform, "platform");
        Objects.requireNonNull(agentVersionId, "agentVersionId");
        Objects.requireNonNull(environmentId, "environmentId");
        Objects.requireNonNull(permissions, "permissions");
        if (platform.projectId() == null
                || platform.sessionId() == null
                || platform.threadId() == null
                || platform.turnId() == null
                || platform.leaseEpoch() == null
                || platform.interaction() == null) {
            throw new IllegalArgumentException("一轮的 PlatformContext 必须包含项目、会话、线程、轮次、租约 epoch 与值守方式");
        }
        if (environmentRevision < 1) {
            throw new IllegalArgumentException("环境修订号必须从 1 开始：" + environmentRevision);
        }
        subagentPolicies = subagentPolicies == null ? Map.of() : Map.copyOf(subagentPolicies);
        customTools = customTools == null ? List.of() : List.copyOf(customTools);
        workspaceFiles = workspaceFiles == null ? Map.of() : Map.copyOf(workspaceFiles);
    }

    /**
     * 创建没有放置文件的一轮的上下文。
     *
     * @param platform 平台上下文
     * @param endUserId 终端用户 ID；可为空
     * @param agentVersionId 智能体版本 ID
     * @param environmentId 环境 ID
     * @param environmentRevision 环境修订号
     * @param permissions 主线程的权限策略
     * @param subagentPolicies 子智能体的权限策略；可为空
     * @param customTools 自定义工具；可为空
     * @param recovery 恢复说明的来源；可为空
     * @param interruptRequested 认领时本轮已有中断请求
     */
    public TurnContext(
            PlatformContext platform,
            String endUserId,
            String agentVersionId,
            String environmentId,
            long environmentRevision,
            PermissionPolicy permissions,
            Map<String, PermissionPolicy> subagentPolicies,
            List<CustomToolDeclaration> customTools,
            RecoveryOrigin recovery,
            boolean interruptRequested) {
        this(
                platform,
                endUserId,
                agentVersionId,
                environmentId,
                environmentRevision,
                permissions,
                subagentPolicies,
                customTools,
                recovery,
                interruptRequested,
                Map.of());
    }

    /**
     * 创建没有中断请求的一轮的上下文。
     *
     * @param platform 平台上下文
     * @param endUserId 终端用户 ID；可为空
     * @param agentVersionId 智能体版本 ID
     * @param environmentId 环境 ID
     * @param environmentRevision 环境修订号
     * @param permissions 主线程的权限策略
     * @param subagentPolicies 子智能体的权限策略；可为空
     * @param customTools 自定义工具；可为空
     * @param recovery 恢复说明的来源；可为空
     */
    public TurnContext(
            PlatformContext platform,
            String endUserId,
            String agentVersionId,
            String environmentId,
            long environmentRevision,
            PermissionPolicy permissions,
            Map<String, PermissionPolicy> subagentPolicies,
            List<CustomToolDeclaration> customTools,
            RecoveryOrigin recovery) {
        this(
                platform,
                endUserId,
                agentVersionId,
                environmentId,
                environmentRevision,
                permissions,
                subagentPolicies,
                customTools,
                recovery,
                false,
                Map.of());
    }

    /**
     * 带上已放置文件的副本。
     *
     * @param files 文件 ID → 相对工作区根的路径
     * @return 新的上下文
     */
    public TurnContext withWorkspaceFiles(Map<String, String> files) {
        return new TurnContext(
                platform,
                endUserId,
                agentVersionId,
                environmentId,
                environmentRevision,
                permissions,
                subagentPolicies,
                customTools,
                recovery,
                interruptRequested,
                files);
    }

    /**
     * 本轮执行的主线程。
     *
     * @return 线程引用
     */
    public ThreadRef thread() {
        return new ThreadRef(platform.sessionId(), platform.threadId());
    }

    /**
     * 本轮的轮次 ID。
     *
     * @return 轮次 ID（{@code trn_…}）
     */
    public String turnId() {
        return platform.turnId();
    }

    /**
     * 本次执行持有的租约 epoch。
     *
     * @return 租约 epoch
     */
    public long leaseEpoch() {
        return platform.leaseEpoch();
    }
}
