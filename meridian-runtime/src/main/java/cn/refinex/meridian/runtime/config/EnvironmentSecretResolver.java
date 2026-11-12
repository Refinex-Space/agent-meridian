package cn.refinex.meridian.runtime.config;

import cn.refinex.meridian.common.error.ForbiddenException;
import cn.refinex.meridian.common.error.NotFoundException;
import cn.refinex.meridian.engine.spi.port.SecretResolver;
import cn.refinex.meridian.engine.spi.port.SecretValue;
import cn.refinex.meridian.runtime.modelrouting.domain.ModelRoutingErrorCode;
import java.util.Objects;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import reactor.core.publisher.Mono;

/**
 * {@link SecretResolver} 的开发实现（T09a；T15a 起只在 {@code catalog-source=file} 的 {@code dev}、{@code test} profile 使用，生产是
 * {@link ControlPlaneSecretResolver}）：把凭据引用当作环境变量名，从环境变量读取明文。
 * 开发配置里只写变量名（例如 {@code DASHSCOPE_API_KEY}），不写凭据本身。
 *
 * <p>只允许读取名字以 {@code _API_KEY} 结尾的大写变量，数据库口令等其他环境变量不能经它读出（架构原则第 5 条"凭据默认不可读"）。
 * 明文只放进 {@link SecretValue}，不记日志。
 *
 * <p>线程安全：无状态。
 */
public final class EnvironmentSecretResolver implements SecretResolver {

    /** 允许读取的环境变量名。 */
    static final Pattern ALLOWED_NAME = Pattern.compile("[A-Z][A-Z0-9_]*_API_KEY");

    private final UnaryOperator<String> environment;

    /** 读取进程环境变量。 */
    public EnvironmentSecretResolver() {
        this(System::getenv);
    }

    /**
     * 指定环境变量来源，供测试使用。
     *
     * @param environment 按变量名返回值，不存在时返回 null
     */
    EnvironmentSecretResolver(UnaryOperator<String> environment) {
        this.environment = Objects.requireNonNull(environment, "environment");
    }

    @Override
    public Mono<SecretValue> resolve(String secretId) {
        return Mono.fromCallable(() -> {
            if (secretId == null || !ALLOWED_NAME.matcher(secretId).matches()) {
                throw new ForbiddenException("开发期的凭据引用只能是以 _API_KEY 结尾的环境变量名：" + secretId);
            }
            String value = environment.apply(secretId);
            if (value == null || value.isBlank()) {
                throw new NotFoundException(
                        ModelRoutingErrorCode.CREDENTIAL_UNAVAILABLE, "凭据未配置：环境变量 " + secretId + " 没有设置");
            }
            return new SecretValue(value);
        });
    }
}
