package cn.refinex.meridian.security.web;

import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ThreadLocalAccessor;
import org.slf4j.MDC;

/**
 * 把 Reactor Context 中的请求 ID 恢复到 MDC 的线程局部访问器（Micrometer context-propagation）。
 *
 * <p>注册后，在开启了 Reactor 自动上下文传播（{@code Hooks.enableAutomaticContextPropagation()}，Spring Boot 中为
 * {@code spring.reactor.context-propagation=auto}）的应用里，算子执行前把 {@code request_id} 写入 MDC，执行后恢复原值。
 */
public final class RequestIdMdcAccessor implements ThreadLocalAccessor<String> {

    /**
     * 向全局注册表注册本访问器；重复注册会替换同键的旧访问器。
     */
    public static void register() {
        ContextRegistry.getInstance().registerThreadLocalAccessor(new RequestIdMdcAccessor());
    }

    @Override
    public Object key() {
        return RequestIds.MDC_KEY;
    }

    @Override
    public String getValue() {
        return MDC.get(RequestIds.MDC_KEY);
    }

    @Override
    public void setValue(String value) {
        MDC.put(RequestIds.MDC_KEY, value);
    }

    @Override
    public void setValue() {
        MDC.remove(RequestIds.MDC_KEY);
    }
}
