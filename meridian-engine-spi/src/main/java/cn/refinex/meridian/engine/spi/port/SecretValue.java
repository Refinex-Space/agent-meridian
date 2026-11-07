package cn.refinex.meridian.engine.spi.port;

import java.util.Objects;

/**
 * 凭据明文。{@link #toString()} 不输出明文，避免被日志、异常信息或调试输出带出（架构原则第 6 条）。
 *
 * <p>只在工具执行或模型调用的边界取出明文（{@link #reveal()}），用完即弃，不缓存到长生命周期对象之外。
 */
public final class SecretValue {

    private final String value;

    /**
     * 包装凭据明文。
     *
     * @param value 明文；必填
     * @throws NullPointerException 明文为空引用时
     */
    public SecretValue(String value) {
        this.value = Objects.requireNonNull(value, "value");
    }

    /**
     * 取出明文。只在写入请求头等边界调用。
     *
     * @return 凭据明文
     */
    public String reveal() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SecretValue that && value.equals(that.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return "SecretValue[***]";
    }
}
