package cn.refinex.meridian.engine.spi.port;

import reactor.core.publisher.Mono;

/**
 * 凭据明文解析：MCP 请求头、联网搜索后端等在工具执行边界注入凭据时使用，由 runtime 的 {@code config} 实现
 * （经 control-plane 内部接口解密），见架构设计「端口」「凭据」。
 *
 * <p>只能解析调用方项目下的凭据，项目从 Reactor Context 中的 {@code PlatformContext} 取；凭据默认不可读
 * （架构原则第 5 条），没有授权时以 {@code ForbiddenException} 失败。
 */
public interface SecretResolver {

    /**
     * 解析一个凭据的明文。
     *
     * @param secretId 凭据 ID（{@code sec_…}）
     * @return 凭据明文；凭据不存在时以 {@code NotFoundException} 失败
     */
    Mono<SecretValue> resolve(String secretId);
}
