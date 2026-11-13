package cn.refinex.meridian.runtime.config;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.common.context.PlatformContext;
import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.engine.agentscope.testkit.assembly.TestSpecs;
import cn.refinex.meridian.engine.spi.engine.AgentEngine;
import cn.refinex.meridian.runtime.session.app.ReservedToolNames;
import java.lang.reflect.Proxy;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import reactor.core.publisher.Mono;

/**
 * 创建会话时取保留工具名（R2 的验收预演发现）：创建会话的用例在 {@code ScopedValue} 上绑定了 {@code PlatformContext}，而内核的
 * {@code reservedToolNames} 在 {@code Blocking} 中从 Reactor Context 取它；新智能体的实例还没缓存时要装配，装配读租户提示词要求有
 * 上下文。此前同步等待时没有把上下文带进去，第一次带自定义工具创建会话得到 500。
 */
class ReservedToolNamesContextTest {

    @Test
    void theCallersPlatformContextReachesTheEngine() {
        AgentEngine engine = (AgentEngine) Proxy.newProxyInstance(
                AgentEngine.class.getClassLoader(), new Class<?>[] {AgentEngine.class}, (proxy, method, args) -> {
                    if (method.getName().equals("reservedToolNames")) {
                        return Mono.deferContextual(view -> Mono.just(PlatformContexts.find(view)
                                .map(context -> Set.of("seen:" + context.tenantId()))
                                .orElse(Set.of("missing"))));
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("engine", engine);
        ReservedToolNames names = new WorkerConfiguration().reservedToolNames(beans.getBeanProvider(AgentEngine.class));
        PlatformContext context =
                PlatformContext.builder().tenantId("ten_r2").projectId("prj_r2").build();

        Set<String> reserved =
                PlatformContexts.call(context, () -> names.of(TestSpecs.agent().build(), TestSpecs.noneEnvironment()));

        assertThat(reserved).containsExactly("seen:ten_r2");
    }
}
