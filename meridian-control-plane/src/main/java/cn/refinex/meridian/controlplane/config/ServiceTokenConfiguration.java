package cn.refinex.meridian.controlplane.config;

import cn.refinex.meridian.controlplane.servicetoken.app.SigningKeySource;
import cn.refinex.meridian.controlplane.servicetoken.infra.DirectorySigningKeys;
import cn.refinex.meridian.security.servicejwt.ServiceCredentialResolver;
import cn.refinex.meridian.security.servicejwt.ServiceJwtVerifier;
import cn.refinex.meridian.security.servicejwt.ServiceJwts;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 服务 JWT 的装配（ADR-0044）：签名密钥的来源（开发方式是目录，生产实现以同类型的 bean 替换），以及 control-plane 自己的 {@code /internal/}
 * 校验器——用进程内的公钥，不经 HTTP 读自己的 JWKS。
 */
@Configuration(proxyBeanMethods = false)
public class ServiceTokenConfiguration {

    /**
     * 签名密钥：目录中的 JWK 文件，没有时生成。
     *
     * @param properties control-plane 配置，取密钥目录
     * @param clock 时钟
     * @return 密钥来源
     * @throws IllegalStateException 没有配置密钥目录时
     */
    @Bean
    @ConditionalOnMissingBean
    public SigningKeySource signingKeySource(ControlPlaneProperties properties, Clock clock) {
        String directory = properties.serviceTokens().keyDirectory();
        if (directory == null || directory.isBlank()) {
            throw new IllegalStateException(
                    "没有配置服务 JWT 的签名密钥目录 meridian.control-plane.service-tokens.key-directory（生产改用密钥管理系统，T28）");
        }
        return new DirectorySigningKeys(Path.of(directory), clock);
    }

    /**
     * control-plane 的服务 JWT 校验器：{@code aud} 必须是 {@value ServiceJwts#CONTROL_PLANE}。
     *
     * @param keys 签名密钥
     * @param properties control-plane 配置，取签发方
     * @return 校验器
     */
    @Bean
    public ServiceCredentialResolver serviceCredentialResolver(
            SigningKeySource keys, ControlPlaneProperties properties) {
        JWKSource<SecurityContext> local =
                (selector, context) -> selector.select(new JWKSet(List.<JWK>copyOf(keys.publicKeys())));
        return new ServiceJwtVerifier(local, properties.serviceTokens().issuer(), ServiceJwts.CONTROL_PLANE);
    }
}
