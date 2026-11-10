package cn.refinex.meridian.controlplane.servicetoken.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.Set;

/**
 * 一个可以换取服务 JWT 的服务客户端（ADR-0044）：服务名、客户端密钥的 SHA-256、允许的目标服务。只保存摘要，按摘要做常量时间比较。
 *
 * @param service 服务名
 * @param secretDigest 客户端密钥的 SHA-256
 * @param audiences 允许换取的目标服务名
 */
public record ServiceClient(String service, byte[] secretDigest, Set<String> audiences) {

    /** 客户端密钥的最短长度：开发与生产都不接受弱密钥。 */
    public static final int MIN_SECRET_LENGTH = 16;

    /** 规整集合。 */
    public ServiceClient {
        secretDigest = secretDigest.clone();
        audiences = Set.copyOf(audiences);
    }

    /**
     * 按配置创建客户端。
     *
     * @param service 服务名；必填
     * @param secret 客户端密钥；必填，至少 {@value #MIN_SECRET_LENGTH} 个字符
     * @param audiences 允许的目标服务；必填，非空
     * @return 客户端
     * @throws IllegalStateException 配置不完整或密钥太短时
     */
    public static ServiceClient of(String service, String secret, Collection<String> audiences) {
        if (service == null || service.isBlank() || audiences == null || audiences.isEmpty()) {
            throw new IllegalStateException("服务客户端必须给出 service 与 audiences：" + service);
        }
        if (secret == null || secret.length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException("服务客户端 " + service + " 的密钥为空或少于 " + MIN_SECRET_LENGTH + " 个字符");
        }
        return new ServiceClient(service, digest(secret), Set.copyOf(audiences));
    }

    /**
     * 密钥是否匹配。
     *
     * @param secret 请求中的密钥
     * @return 匹配时为 true
     */
    public boolean matches(String secret) {
        return MessageDigest.isEqual(secretDigest, digest(secret));
    }

    /**
     * 能否换取某个目标服务的令牌。
     *
     * @param audience 目标服务名
     * @return 能时为 true
     */
    public boolean mayCall(String audience) {
        return audiences.contains(audience);
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 规定必须提供的算法
            throw new IllegalStateException(e);
        }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ServiceClient client && client.service.equals(service);
    }

    @Override
    public int hashCode() {
        return service.hashCode();
    }

    /**
     * 不打印摘要。
     *
     * @return 描述
     */
    @Override
    public String toString() {
        return "ServiceClient[service=" + service + ", audiences=" + audiences + "]";
    }
}
