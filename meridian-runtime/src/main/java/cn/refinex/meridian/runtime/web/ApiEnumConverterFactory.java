package cn.refinex.meridian.runtime.web;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.convert.converter.ConverterFactory;

/**
 * 查询参数到生成的 DTO 枚举的转换：按规范中的取值（例如 {@code resolved}）而不是 Java 常量名转换，调用枚举的
 * {@code fromValue(String)}。openapi-generator 本该随支撑文件生成同样作用的配置，我们只生成接口（ADR-0029），所以在这里补上。
 * 取值未知时抛 {@link IllegalArgumentException}，WebFlux 映射为 400。
 */
public final class ApiEnumConverterFactory implements ConverterFactory<String, Enum<?>> {

    /** 生成的 DTO 所在的包。 */
    static final String MODEL_PACKAGE = "cn.refinex.meridian.api.model.";

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <T extends Enum<?>> Converter<String, T> getConverter(Class<T> targetType) {
        Method fromValue = fromValue(targetType);
        if (fromValue == null) {
            return source -> (T) Enum.valueOf((Class) targetType, source);
        }
        return source -> {
            try {
                return (T) fromValue.invoke(null, source);
            } catch (ReflectiveOperationException e) {
                throw new IllegalArgumentException("无法转换为 " + targetType.getSimpleName() + "：" + source, e);
            }
        };
    }

    private static Method fromValue(Class<?> type) {
        if (!type.getName().startsWith(MODEL_PACKAGE)) {
            return null;
        }
        try {
            Method method = type.getMethod("fromValue", String.class);
            return Modifier.isStatic(method.getModifiers()) ? method : null;
        } catch (NoSuchMethodException e) {
            return null;
        }
    }
}
