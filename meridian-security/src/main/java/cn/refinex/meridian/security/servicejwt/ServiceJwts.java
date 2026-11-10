package cn.refinex.meridian.security.servicejwt;

import java.time.Duration;

/**
 * 服务 JWT 的约定（架构设计「认证」中"服务之间"一行，ADR-0044）：签发方、校验方与签发客户端共用的常量。
 */
public final class ServiceJwts {

    /** control-plane 的令牌端点（OAuth2 客户端凭据模式）。 */
    public static final String TOKEN_PATH = "/oauth2/token";

    /** control-plane 的公钥端点（JWK Set）。 */
    public static final String JWKS_PATH = "/oauth2/jwks";

    /** 默认的签发方（{@code iss}）。 */
    public static final String DEFAULT_ISSUER = "meridian-control-plane";

    /** 令牌有效期：5 分钟（架构设计「认证」）。 */
    public static final Duration TOKEN_TTL = Duration.ofMinutes(5);

    /** 校验 {@code exp}、{@code iat} 时容许的时钟偏差。 */
    public static final Duration CLOCK_SKEW = Duration.ofSeconds(30);

    /** 唯一的签名算法。 */
    public static final String ALGORITHM = "RS256";

    /** 令牌端点唯一支持的授权类型。 */
    public static final String GRANT_TYPE = "client_credentials";

    /** 服务名：control-plane。 */
    public static final String CONTROL_PLANE = "control-plane";

    /** 服务名：runtime。 */
    public static final String RUNTIME = "runtime";

    /** 服务名：knowledge。 */
    public static final String KNOWLEDGE = "knowledge";

    /** 服务名：沙箱出网代理（ADR-0044）。 */
    public static final String EGRESS_PROXY = "egress-proxy";

    private ServiceJwts() {}
}
