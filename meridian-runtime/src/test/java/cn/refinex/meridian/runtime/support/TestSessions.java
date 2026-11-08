package cn.refinex.meridian.runtime.support;

import cn.refinex.meridian.common.context.Interaction;
import cn.refinex.meridian.common.context.PlatformContext;
import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.common.id.IdPrefix;
import cn.refinex.meridian.common.id.Ids;
import cn.refinex.meridian.engine.spi.engine.input.InboundMessage;
import cn.refinex.meridian.engine.spi.engine.input.MessageContent;
import cn.refinex.meridian.engine.spi.engine.input.TurnInput;
import cn.refinex.meridian.engine.spi.spec.CustomToolDeclaration;
import cn.refinex.meridian.runtime.dispatch.domain.NewTurn;
import cn.refinex.meridian.runtime.session.domain.NewSession;
import cn.refinex.meridian.runtime.session.domain.NewThread;
import cn.refinex.meridian.runtime.session.infra.SessionRepository;
import cn.refinex.meridian.runtime.session.infra.ThreadRepository;
import java.time.Clock;
import java.util.List;

/** 测试数据：经会话与线程仓储创建会话及其主线程，构造轮次输入。每次都用新的随机 ID，测试之间互不干扰。 */
public final class TestSessions {

    private TestSessions() {}

    /**
     * 在新租户、新项目下创建一个会话与主线程。
     *
     * @param clock 时钟
     * @return 会话
     */
    public static TestSession create(Clock clock) {
        return create(Ids.newId(IdPrefix.TENANT), clock);
    }

    /**
     * 在指定租户、新项目下创建一个会话与主线程。
     *
     * @param tenantId 租户 ID
     * @param clock 时钟
     * @return 会话
     */
    public static TestSession create(String tenantId, Clock clock) {
        return create(tenantId, clock, Ids.newId(IdPrefix.AGENT_VERSION), Ids.newId(IdPrefix.ENVIRONMENT), 1);
    }

    /**
     * 在新租户、新项目下创建一个绑定指定智能体版本与环境的会话，Worker 执行它时按这些 ID 取配置。
     *
     * @param clock 时钟
     * @param agentVersionId 智能体版本 ID
     * @param environmentId 环境 ID
     * @param environmentRevision 环境修订号
     * @return 会话
     */
    public static TestSession create(
            Clock clock, String agentVersionId, String environmentId, long environmentRevision) {
        return create(Ids.newId(IdPrefix.TENANT), clock, agentVersionId, environmentId, environmentRevision);
    }

    private static TestSession create(
            String tenantId, Clock clock, String agentVersionId, String environmentId, long environmentRevision) {
        return create(
                tenantId, clock, agentVersionId, environmentId, environmentRevision, Interaction.ATTENDED, List.of());
    }

    /**
     * 在新租户、新项目下创建一个指定值守方式与自定义工具的会话（T08：无人值守、自定义工具）。
     *
     * @param clock 时钟
     * @param agentVersionId 智能体版本 ID
     * @param environmentId 环境 ID
     * @param environmentRevision 环境修订号
     * @param interaction 值守方式
     * @param customTools 会话声明的自定义工具
     * @return 会话
     */
    public static TestSession create(
            Clock clock,
            String agentVersionId,
            String environmentId,
            long environmentRevision,
            Interaction interaction,
            List<CustomToolDeclaration> customTools) {
        return create(
                Ids.newId(IdPrefix.TENANT),
                clock,
                agentVersionId,
                environmentId,
                environmentRevision,
                interaction,
                customTools);
    }

    /**
     * 在已有会话所在的租户与项目下再创建一个会话（T08：项目级"记住此决定"对同项目的其他会话生效）。
     *
     * @param sameProject 已有会话
     * @param clock 时钟
     * @param agentVersionId 智能体版本 ID
     * @param environmentId 环境 ID
     * @param environmentRevision 环境修订号
     * @return 新会话
     */
    public static TestSession another(
            TestSession sameProject,
            Clock clock,
            String agentVersionId,
            String environmentId,
            long environmentRevision) {
        return insert(
                new TestSession(
                        sameProject.tenantId(),
                        sameProject.projectId(),
                        Ids.newId(IdPrefix.SESSION),
                        Ids.newId(IdPrefix.THREAD)),
                clock,
                agentVersionId,
                environmentId,
                environmentRevision,
                Interaction.ATTENDED,
                List.of());
    }

    private static TestSession create(
            String tenantId,
            Clock clock,
            String agentVersionId,
            String environmentId,
            long environmentRevision,
            Interaction interaction,
            List<CustomToolDeclaration> customTools) {
        return insert(
                new TestSession(
                        tenantId, Ids.newId(IdPrefix.PROJECT), Ids.newId(IdPrefix.SESSION), Ids.newId(IdPrefix.THREAD)),
                clock,
                agentVersionId,
                environmentId,
                environmentRevision,
                interaction,
                customTools);
    }

    private static TestSession insert(
            TestSession session,
            Clock clock,
            String agentVersionId,
            String environmentId,
            long environmentRevision,
            Interaction interaction,
            List<CustomToolDeclaration> customTools) {
        PlatformContexts.run(session.context(), () -> {
            new SessionRepository(RuntimeTestDatabase.dataSource())
                    .insert(
                            new NewSession(
                                    session.sessionId(),
                                    agentVersionId,
                                    environmentId,
                                    environmentRevision,
                                    "u_1024",
                                    interaction,
                                    null,
                                    null,
                                    customTools),
                            clock.instant());
            new ThreadRepository(RuntimeTestDatabase.dataSource())
                    .insert(NewThread.main(session.mainThreadId(), session.sessionId(), "assistant"), clock.instant());
        });
        return session;
    }

    /**
     * 一条用户消息的输入。
     *
     * @param text 消息文本
     * @return 输入
     */
    public static TurnInput messages(String text) {
        return new TurnInput.NewMessages(
                List.of(new InboundMessage(Ids.newId(IdPrefix.EVENT), List.of(new MessageContent.Text(text)))), null);
    }

    /**
     * 测试会话。
     *
     * @param tenantId 租户 ID
     * @param projectId 项目 ID
     * @param sessionId 会话 ID
     * @param mainThreadId 主线程 ID
     */
    public record TestSession(String tenantId, String projectId, String sessionId, String mainThreadId) {

        /**
         * 会话所在的平台上下文（没有线程、轮次与 epoch）。
         *
         * @return 上下文
         */
        public PlatformContext context() {
            return PlatformContext.builder()
                    .tenantId(tenantId)
                    .projectId(projectId)
                    .sessionId(sessionId)
                    .build();
        }

        /**
         * 主线程上一条用户消息的入队参数。
         *
         * @param text 消息文本
         * @return 入队参数
         */
        public NewTurn turn(String text) {
            return NewTurn.of(tenantId, projectId, sessionId, mainThreadId, messages(text));
        }

        /**
         * 本会话 AgentScope 的 userId（{@code {project_id}:{end_user_id}}，架构设计「身份映射」）。
         *
         * @return userId
         */
        public String userId() {
            return projectId + ":u_1024";
        }
    }
}
