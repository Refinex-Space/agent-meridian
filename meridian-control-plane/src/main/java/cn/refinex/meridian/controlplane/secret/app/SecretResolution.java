package cn.refinex.meridian.controlplane.secret.app;

import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.common.error.MeridianException;
import cn.refinex.meridian.common.error.NotFoundException;
import cn.refinex.meridian.controlplane.audit.app.AdminAudit;
import cn.refinex.meridian.controlplane.secret.domain.SecretCipher;
import cn.refinex.meridian.controlplane.secret.domain.SecretErrorCode;
import cn.refinex.meridian.controlplane.secret.infra.SecretRepository;
import cn.refinex.meridian.controlplane.web.Scopes;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 给 runtime 解析凭据明文（{@code POST /internal/v1/secrets:resolve}，ADR-0045 第 3 条）。凭据的租户必须等于请求中的租户，带项目的
 * 凭据还要求项目相同；不满足与不存在相同。每次成功的解析在同一事务中写审计 {@code secret.resolved} 并更新最后使用时间，事务提交之后
 * 才返回明文：审计写不进时不交出明文。
 *
 * <p>明文只在本方法返回的对象里，不进日志与异常消息。
 */
@Service
public class SecretResolution {

    private static final Logger log = LoggerFactory.getLogger(SecretResolution.class);

    private final SecretRepository secrets;
    private final DataKeys dataKeys;
    private final AdminAudit audit;
    private final TransactionTemplate transactions;
    private final Clock clock;

    /**
     * 创建用例。
     *
     * @param secrets 凭据仓储
     * @param dataKeys 按租户的数据密钥
     * @param audit 审计
     * @param transactions 事务模板
     * @param clock 时钟
     */
    public SecretResolution(
            SecretRepository secrets,
            DataKeys dataKeys,
            AdminAudit audit,
            TransactionTemplate transactions,
            Clock clock) {
        this.secrets = secrets;
        this.dataKeys = dataKeys;
        this.audit = audit;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * 解析一个凭据。
     *
     * @param caller 调用方服务名
     * @param tenantId 调用方租户
     * @param projectId 调用方项目；为空表示没有项目上下文，只能解析租户级凭据
     * @param secretId 凭据 ID
     * @return 凭据 ID、版本与明文
     * @throws NotFoundException 凭据不存在或不属于调用方时
     */
    public Resolved resolve(String caller, String tenantId, String projectId, String secretId) {
        SecretRepository.Stored stored = PlatformContexts.call(
                        Scopes.tenant(tenantId), () -> secrets.findStored(secretId))
                .filter(s -> s.secret().usableBy(tenantId, projectId))
                .orElseThrow(() -> new NotFoundException(SecretErrorCode.NOT_FOUND, "凭据不存在：" + secretId));
        byte[] dataKey = dataKeys.forTenant(tenantId);
        byte[] plaintext;
        try {
            plaintext = SecretCipher.decrypt(
                    dataKey,
                    stored.ciphertext(),
                    SecretCipher.secretAad(tenantId, secretId, stored.secret().version()));
        } catch (IllegalStateException e) {
            log.error(
                    "凭据无法解密 tenantId={} secretId={} version={}",
                    tenantId,
                    secretId,
                    stored.secret().version());
            throw new MeridianException(SecretErrorCode.ENCRYPTION_UNAVAILABLE, "凭据无法解密，请联系管理员", e);
        }
        PlatformContexts.run(
                Scopes.tenant(tenantId),
                () -> transactions.executeWithoutResult(status -> {
                    secrets.touch(secretId, clock.instant());
                    audit.recordService(
                            "secret.resolved",
                            caller,
                            tenantId,
                            projectId,
                            "secret",
                            secretId,
                            Map.of("version", stored.secret().version()));
                }));
        return new Resolved(secretId, stored.secret().version(), new String(plaintext, StandardCharsets.UTF_8));
    }

    /**
     * 解析结果。
     *
     * @param secretId 凭据 ID
     * @param version 版本
     * @param value 明文；{@link #toString()} 不输出它
     */
    public record Resolved(String secretId, int version, String value) {

        /**
         * 创建结果。
         *
         * @throws NullPointerException 缺少字段时
         */
        public Resolved {
            Objects.requireNonNull(secretId, "secretId");
            Objects.requireNonNull(value, "value");
        }

        /**
         * 不输出明文。
         *
         * @return 不含明文的描述
         */
        @Override
        public String toString() {
            return "Resolved[secretId=" + secretId + ", version=" + version + "]";
        }
    }
}
