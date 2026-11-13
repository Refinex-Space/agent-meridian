package cn.refinex.meridian.controlplane.catalog.app;

import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.common.error.NotFoundException;
import cn.refinex.meridian.common.net.EndpointPolicy;
import cn.refinex.meridian.controlplane.audit.app.AdminAudit;
import cn.refinex.meridian.controlplane.catalog.domain.CatalogErrorCode;
import cn.refinex.meridian.controlplane.catalog.domain.ConnectivityCheck;
import cn.refinex.meridian.controlplane.catalog.domain.ProviderAccount;
import cn.refinex.meridian.controlplane.catalog.infra.ProviderAccountRepository;
import cn.refinex.meridian.controlplane.secret.app.SecretResolution;
import cn.refinex.meridian.controlplane.web.Endpoints;
import cn.refinex.meridian.controlplane.web.Scopes;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 供应商账号的连通性测试（{@code :test}，ADR-0053）：端点再按地址策略校验一次，以 control-plane 的身份解析凭据，探测一次，把结果写进
 * {@code last_check} 并写审计 {@code provider_account.tested}。探测在事务之外进行，不占数据库连接。线程安全：无状态。
 */
@Service
public class ProviderConnectivity {

    /** 解析凭据时的调用方。 */
    static final String CALLER = "control-plane";

    private final ProviderAccountRepository accounts;
    private final SecretResolution resolution;
    private final EndpointPolicy endpoints;
    private final ProviderProbe probe;
    private final AdminAudit audit;
    private final TransactionTemplate transactions;

    /**
     * 创建用例。
     *
     * @param accounts 供应商账号仓储
     * @param resolution 凭据解析
     * @param endpoints 端点策略
     * @param probe 探测器
     * @param audit 审计
     * @param transactions 事务模板
     */
    public ProviderConnectivity(
            ProviderAccountRepository accounts,
            SecretResolution resolution,
            EndpointPolicy endpoints,
            ProviderProbe probe,
            AdminAudit audit,
            TransactionTemplate transactions) {
        this.accounts = accounts;
        this.resolution = resolution;
        this.endpoints = endpoints;
        this.probe = probe;
        this.audit = audit;
        this.transactions = transactions;
    }

    /**
     * 测试一个账号。
     *
     * @param actor 操作者
     * @param tenantId 租户 ID
     * @param id 账号 ID
     * @return 结果；连不上、凭据无效也是结果，不是异常
     * @throws NotFoundException 账号不存在时
     */
    public ConnectivityCheck test(String actor, String tenantId, String id) {
        ProviderAccount account = PlatformContexts.call(
                Scopes.tenant(tenantId),
                () -> accounts.find(id, false)
                        .orElseThrow(() ->
                                new NotFoundException(CatalogErrorCode.PROVIDER_ACCOUNT_NOT_FOUND, "供应商账号不存在：" + id)));
        Endpoints.check(endpoints, account.baseUrl(), "base_url");
        String apiKey = account.secretId() == null
                ? null
                : resolution.resolve(CALLER, tenantId, null, account.secretId()).value();
        ConnectivityCheck check = probe.probe(account.provider(), account.baseUrl(), apiKey);
        PlatformContexts.run(
                Scopes.tenant(tenantId),
                () -> transactions.executeWithoutResult(status -> {
                    accounts.saveCheck(id, check);
                    Map<String, Object> details = new LinkedHashMap<>();
                    details.put("status", check.status());
                    details.put("http_status", check.httpStatus());
                    details.put("latency_ms", check.latencyMs());
                    audit.record("provider_account.tested", actor, tenantId, null, "provider_account", id, details);
                }));
        return check;
    }
}
