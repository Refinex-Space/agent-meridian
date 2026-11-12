package cn.refinex.meridian.engine.spi.port;

import reactor.core.publisher.Mono;

/**
 * 凭据明文解析：模型调用、MCP 请求头、联网搜索后端等在执行边界注入凭据时使用，由 runtime 的 {@code config} 实现
 * （经 control-plane 内部接口 {@code secrets:resolve} 解密，明文只在内存中缓存 5 分钟），见架构设计「端口」「凭据」。
 *
 * <p>只能解析调用方租户的租户级凭据与调用方项目的项目级凭据，租户与项目从 Reactor Context 中的 {@code PlatformContext} 取
 * （ADR-0045）；不属于调用方的凭据与不存在相同。凭据默认不可读（架构原则第 5 条）。
 */
public interface SecretResolver {

    /**
     * 解析一个凭据的明文。
     *
     * @param secretId 凭据 ID（{@code sec_…}）
     * @return 凭据明文；凭据不存在或不属于调用方时以 {@code NotFoundException} 失败
     */
    Mono<SecretValue> resolve(String secretId);
}
