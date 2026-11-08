package cn.refinex.meridian.security.servicejwt;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 开发期的 {@link ServiceCredentialResolver}：凭据来自配置 {@link DevServiceCredentialProperties}，只在 {@code dev} 与 {@code test}
 * profile 生效（T09b）；T14b 换成服务 JWT。
 *
 * <p>只保存凭据的 SHA-256，按摘要做常量时间比较。线程安全：创建后不可变。
 */
public final class DevServiceCredentialResolver implements ServiceCredentialResolver {

    private final List<Entry> entries;

    /**
     * 按配置创建解析器。
     *
     * @param properties 开发期服务凭据配置
     * @throws IllegalStateException 有空的凭据或服务名时
     */
    public DevServiceCredentialResolver(DevServiceCredentialProperties properties) {
        List<Entry> list = new ArrayList<>();
        for (DevServiceCredentialProperties.Credential credential : properties.credentials()) {
            if (isBlank(credential.credential()) || isBlank(credential.service())) {
                throw new IllegalStateException("开发期服务凭据必须给出 credential 与 service：" + credential.service());
            }
            list.add(new Entry(sha256(credential.credential()), new ServicePrincipal(credential.service())));
        }
        this.entries = List.copyOf(list);
    }

    @Override
    public Optional<ServicePrincipal> resolve(String credential) {
        byte[] digest = sha256(credential);
        ServicePrincipal found = null;
        for (Entry entry : entries) {
            if (MessageDigest.isEqual(entry.digest(), digest)) {
                found = entry.principal();
            }
        }
        return Optional.ofNullable(found);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 规定必须提供的算法
            throw new IllegalStateException(e);
        }
    }

    /** 一个凭据的摘要与主体。 */
    private record Entry(byte[] digest, ServicePrincipal principal) {}
}
