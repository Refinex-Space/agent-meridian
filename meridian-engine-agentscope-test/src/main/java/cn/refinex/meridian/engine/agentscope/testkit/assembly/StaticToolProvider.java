package cn.refinex.meridian.engine.agentscope.testkit.assembly;

import cn.refinex.meridian.engine.agentscope.assembly.AgentProfile;
import cn.refinex.meridian.engine.agentscope.assembly.ToolProvider;
import io.agentscope.core.tool.AgentTool;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 返回固定工具的工具提供者替身：只提供名字在 {@link AgentProfile#tools()} 中的工具（与生产实现按工具集选择一致），
 * 另外总是提供 {@code alwaysOn} 中的工具；每次装配附带一个记录关闭次数的资源。
 *
 * <p>线程安全：可以被并发装配调用。
 *
 * <p>见架构设计「测试策略」「平台工具」。
 */
public final class StaticToolProvider implements ToolProvider {

    private final List<AgentTool> tools;
    private final List<AgentTool> alwaysOn;
    private final List<AgentProfile> profiles = new CopyOnWriteArrayList<>();
    private final List<TrackedResource> resources = new CopyOnWriteArrayList<>();

    /**
     * 创建提供者。
     *
     * @param tools 按工具集筛选的工具
     * @param alwaysOn 总是提供的工具，模拟测试专用的工具
     */
    public StaticToolProvider(List<AgentTool> tools, List<AgentTool> alwaysOn) {
        this.tools = List.copyOf(tools);
        this.alwaysOn = List.copyOf(alwaysOn);
    }

    /**
     * 总是提供给定工具的提供者。
     *
     * @param tools 工具
     * @return 提供者
     */
    public static StaticToolProvider always(AgentTool... tools) {
        return new StaticToolProvider(List.of(), List.of(tools));
    }

    @Override
    public ToolBundle tools(AgentProfile profile) {
        profiles.add(profile);
        TrackedResource resource = new TrackedResource();
        resources.add(resource);
        List<AgentTool> selected = new ArrayList<>(alwaysOn);
        tools.stream().filter(t -> profile.tools().contains(t.getName())).forEach(selected::add);
        return new ToolBundle(selected, List.of(resource));
    }

    /**
     * 收到的全部装配对象，按调用顺序。
     *
     * @return 装配对象
     */
    public List<AgentProfile> profiles() {
        return List.copyOf(profiles);
    }

    /**
     * 每次装配附带的资源，按装配顺序。
     *
     * @return 资源
     */
    public List<TrackedResource> resources() {
        return List.copyOf(resources);
    }

    /** 记录关闭次数的资源，模拟 MCP 客户端等需要随实例关闭的对象。 */
    public static final class TrackedResource implements AutoCloseable {

        private final AtomicInteger closeCount = new AtomicInteger();

        @Override
        public void close() {
            closeCount.incrementAndGet();
        }

        /**
         * 被关闭的次数。
         *
         * @return 次数
         */
        public int closeCount() {
            return closeCount.get();
        }
    }
}
