package cn.refinex.meridian.controlplane.servicetoken.api;

import cn.refinex.meridian.api.internal.model.Jwk;
import cn.refinex.meridian.api.internal.model.JwkSet;
import cn.refinex.meridian.api.internal.model.ServiceToken;
import cn.refinex.meridian.controlplane.openapi.internal.InternalServiceTokensApi;
import cn.refinex.meridian.controlplane.servicetoken.app.ServiceTokenIssuer;
import cn.refinex.meridian.controlplane.servicetoken.app.SigningKeySource;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 令牌端点与 JWKS（ADR-0044）。令牌端点自己认证客户端（HTTP Basic，RFC 6749 2.3.1 的表单编码），不经服务 JWT 过滤器；JWKS 是公钥，
 * 不需要认证。
 */
@RestController
public class ServiceTokensApiController implements InternalServiceTokensApi {

    private static final String BASIC = "basic ";

    private final ServiceTokenIssuer issuer;
    private final SigningKeySource keys;
    private final HttpServletRequest request;

    /**
     * 创建处理器。
     *
     * @param issuer 签发用例
     * @param keys 签名密钥
     * @param request 当前请求（Spring 注入的代理）
     */
    public ServiceTokensApiController(ServiceTokenIssuer issuer, SigningKeySource keys, HttpServletRequest request) {
        this.issuer = issuer;
        this.keys = keys;
        this.request = request;
    }

    @Override
    public ResponseEntity<ServiceToken> issueServiceToken(String grantType, String audience) {
        String[] credentials = basic(request.getHeader(HttpHeaders.AUTHORIZATION));
        ServiceTokenIssuer.IssuedToken token = issuer.issue(credentials[0], credentials[1], grantType, audience);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new ServiceToken(
                        token.token(), "Bearer", Math.toIntExact(token.ttl().toSeconds())));
    }

    @Override
    public ResponseEntity<JwkSet> getServiceJwks() {
        return ResponseEntity.ok(new JwkSet(keys.publicKeys().stream()
                .map(key -> new Jwk(
                        "RSA",
                        key.getKeyID(),
                        "sig",
                        "RS256",
                        key.getModulus().toString(),
                        key.getPublicExponent().toString()))
                .toList()));
    }

    /** 解析 HTTP Basic：返回 {服务名, 密钥}，缺少或格式不对时两者都为空。 */
    private static String[] basic(String authorization) {
        if (authorization == null || !authorization.toLowerCase(Locale.ROOT).startsWith(BASIC)) {
            return new String[2];
        }
        try {
            String pair = new String(
                    Base64.getDecoder()
                            .decode(authorization.substring(BASIC.length()).strip()),
                    StandardCharsets.UTF_8);
            int colon = pair.indexOf(':');
            if (colon < 0) {
                return new String[2];
            }
            return new String[] {
                URLDecoder.decode(pair.substring(0, colon), StandardCharsets.UTF_8),
                URLDecoder.decode(pair.substring(colon + 1), StandardCharsets.UTF_8)
            };
        } catch (IllegalArgumentException e) {
            return new String[2];
        }
    }
}
