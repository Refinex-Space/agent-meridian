package cn.refinex.meridian.engine.agentscope.assembly;

import java.lang.reflect.Field;

/**
 * 读取 AgentScope 对象的私有字段，用于断言装配结果中没有公开读取方法的配置（例如中间件内部的配置对象）。
 *
 * <p>字段名取自 AgentScope 2.0.4 源码；升级后字段改名会让测试失败，这正是需要重新核对装配的信号。
 */
final class Reflection {

    private Reflection() {}

    /** 读取 {@code target} 及其父类中名为 {@code name} 的字段。 */
    static Object field(Object target, String name) {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException e) {
                type = type.getSuperclass();
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
        throw new IllegalArgumentException(target.getClass().getName() + " 没有字段 " + name);
    }
}
