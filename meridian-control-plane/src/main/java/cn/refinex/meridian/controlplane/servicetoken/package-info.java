/**
 * 服务 JWT 的签发（架构设计「认证」中"服务之间"一行，ADR-0044）：服务客户端用密钥在 {@code POST /oauth2/token} 换取 5 分钟的 RS256
 * 令牌，公钥在 {@code GET /oauth2/jwks}。签名密钥经 {@link cn.refinex.meridian.controlplane.servicetoken.app.SigningKeySource} 取得：
 * 开发方式是目录中的 JWK 文件（{@code infra.DirectorySigningKeys}），生产从密钥管理系统加载（T28）。校验在 {@code meridian-security}
 * 的 {@code servicejwt} 包。
 */
package cn.refinex.meridian.controlplane.servicetoken;
