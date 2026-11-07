package cn.refinex.meridian.engine.agentscope.testkit.assembly;

import cn.refinex.meridian.engine.agentscope.assembly.AgentProfile;
import cn.refinex.meridian.engine.agentscope.assembly.MiddlewareProvider;
import io.agentscope.core.middleware.MiddlewareBase;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 记录装配对象的中间件提供者替身：主智能体与子智能体各返回一组固定的中间件。
 *
 * <p>线程安全：可以被并发装配调用。
 */
public final class RecordingMiddlewareProvider implements MiddlewareProvider {

    private final List<MiddlewareBase> mainMiddlewares;
    private final List<MiddlewareBase> subagentMiddlewares;
    private final List<AgentProfile> profiles = new CopyOnWriteArrayList<>();

    /**
     * 创建提供者。
     *
     * @param mainMiddlewares 主智能体的中间件
     * @param subagentMiddlewares 子智能体的中间件
     */
    public RecordingMiddlewareProvider(List<MiddlewareBase> mainMiddlewares, List<MiddlewareBase> subagentMiddlewares) {
        this.mainMiddlewares = List.copyOf(mainMiddlewares);
        this.subagentMiddlewares = List.copyOf(subagentMiddlewares);
    }

    /**
     * 不提供任何中间件。
     *
     * @return 提供者
     */
    public static RecordingMiddlewareProvider none() {
        return new RecordingMiddlewareProvider(List.of(), List.of());
    }

    @Override
    public List<MiddlewareBase> middlewares(AgentProfile profile) {
        profiles.add(profile);
        return profile.isSubagent() ? subagentMiddlewares : mainMiddlewares;
    }

    /**
     * 收到的全部装配对象，按调用顺序。
     *
     * @return 装配对象
     */
    public List<AgentProfile> profiles() {
        return List.copyOf(profiles);
    }
}
