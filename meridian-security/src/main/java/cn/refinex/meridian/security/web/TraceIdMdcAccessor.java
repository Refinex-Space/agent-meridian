package cn.refinex.meridian.security.web;

import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ThreadLocalAccessor;
import org.slf4j.MDC;

/**
 * 把 Reactor Context 中调用方的 trace-id 恢复到 MDC 的线程局部访问器，机制同 {@link RequestIdMdcAccessor}。
 */
public final class TraceIdMdcAccessor implements ThreadLocalAccessor<String> {

    /**
     * 向全局注册表注册本访问器；重复注册会替换同键的旧访问器。
     */
    public static void register() {
        ContextRegistry.getInstance().registerThreadLocalAccessor(new TraceIdMdcAccessor());
    }

    @Override
    public Object key() {
        return TraceParents.MDC_KEY;
    }

    @Override
    public String getValue() {
        return MDC.get(TraceParents.MDC_KEY);
    }

    @Override
    public void setValue(String value) {
        MDC.put(TraceParents.MDC_KEY, value);
    }

    @Override
    public void setValue() {
        MDC.remove(TraceParents.MDC_KEY);
    }
}
