package cn.refinex.meridian.controlplane.servicetoken.app;

import com.nimbusds.jose.jwk.RSAKey;
import java.util.List;

/**
 * 服务 JWT 签名密钥的来源（ADR-0044 的扩展点）：开发实现是目录中的 JWK 文件（{@code infra.DirectorySigningKeys}），生产实现从密钥
 * 管理系统加载（T28，部署文档待办）。多副本的 control-plane 必须共用同一组密钥。实现必须线程安全。
 */
public interface SigningKeySource {

    /**
     * 当前签名的密钥（含私钥）。
     *
     * @return 密钥
     */
    RSAKey current();

    /**
     * 发布到 JWKS 的公钥：当前密钥在前，之后是仍在验证期内的旧密钥。
     *
     * @return 公钥
     */
    List<RSAKey> publicKeys();

    /**
     * 轮换：生成新的签名密钥，只保留上一把用于验证（旧令牌最多再有效 5 分钟）。
     */
    void rotate();
}
