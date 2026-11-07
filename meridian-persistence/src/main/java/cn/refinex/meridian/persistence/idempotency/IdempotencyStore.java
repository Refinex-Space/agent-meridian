package cn.refinex.meridian.persistence.idempotency;

import cn.refinex.meridian.persistence.jdbc.SchemaNames;
import cn.refinex.meridian.persistence.tenant.TenantScope;
import cn.refinex.meridian.persistence.tenant.TenantScopedRepository;
import java.time.Instant;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * 幂等键表 {@code <schema>.idempotency_keys} 的读写，租户级作用域：同一个键只在本租户、本调用方内有效。
 *
 * <p>每个方法单独执行（自动提交），不加入业务事务：幂等记录必须在业务处理之前可见，
 * 才能挡住并发的重复请求。
 */
public class IdempotencyStore extends TenantScopedRepository {

    private final String acquireSql;
    private final String findSql;
    private final String completeSql;
    private final String releaseSql;

    /**
     * 创建幂等键存储。
     *
     * @param dataSource 应用的数据源
     * @param schema 幂等键表所在的 schema（服务自己的 schema）
     */
    public IdempotencyStore(DataSource dataSource, String schema) {
        super(dataSource, TenantScope.TENANT);
        String table = SchemaNames.require(schema) + ".idempotency_keys";
        // 键不存在、已过期、或处理中的记录已超时（处理它的节点大概率已崩溃）时，由本次请求接手
        this.acquireSql = "INSERT INTO " + table
                + " (tenant_id, principal, idempotency_key, request_hash, status, created_at, expires_at)"
                + " VALUES (:tenant_id, :principal, :key, :hash, 'in_progress', :now, :expires_at)"
                + " ON CONFLICT (tenant_id, principal, idempotency_key) DO UPDATE"
                + " SET request_hash = EXCLUDED.request_hash, status = 'in_progress', response_status = NULL,"
                + " response_body = NULL, created_at = EXCLUDED.created_at, expires_at = EXCLUDED.expires_at"
                + " WHERE idempotency_keys.expires_at <= :now"
                + " OR (idempotency_keys.status = 'in_progress' AND idempotency_keys.created_at <= :stale_before)";
        this.findSql = "SELECT request_hash, status, response_status, response_body FROM " + table
                + " WHERE tenant_id = :tenant_id AND principal = :principal AND idempotency_key = :key";
        this.completeSql = "UPDATE " + table
                + " SET status = 'completed', response_status = :response_status, response_body = :response_body"
                + " WHERE tenant_id = :tenant_id AND principal = :principal AND idempotency_key = :key"
                + " AND status = 'in_progress'";
        this.releaseSql = "DELETE FROM " + table
                + " WHERE tenant_id = :tenant_id AND principal = :principal AND idempotency_key = :key"
                + " AND status = 'in_progress'";
    }

    /**
     * 尝试占用一个键：成功表示本次请求负责执行并保存结果。
     *
     * @param principal 调用方身份
     * @param key 幂等键
     * @param requestHash 请求指纹
     * @param now 当前时间
     * @param expiresAt 记录的过期时间
     * @param staleBefore 早于此时开始、仍在处理中的记录视为已放弃
     * @return 占用成功返回 {@code true}
     */
    public boolean tryAcquire(
            String principal, String key, String requestHash, Instant now, Instant expiresAt, Instant staleBefore) {
        return sql(acquireSql)
                        .param("principal", principal)
                        .param("key", key)
                        .param("hash", requestHash)
                        .param("now", now)
                        .param("expires_at", expiresAt)
                        .param("stale_before", staleBefore)
                        .update()
                == 1;
    }

    /**
     * 读取一个键的当前记录。
     *
     * @param principal 调用方身份
     * @param key 幂等键
     * @return 记录，不存在时为空
     */
    public Optional<Entry> find(String principal, String key) {
        return sql(findSql)
                .param("principal", principal)
                .param("key", key)
                .query((rs, row) -> new Entry(
                        rs.getString("request_hash"),
                        "completed".equals(rs.getString("status")),
                        rs.getObject("response_status", Integer.class),
                        rs.getString("response_body")))
                .optional();
    }

    /**
     * 保存首次请求的结果。
     *
     * @param principal 调用方身份
     * @param key 幂等键
     * @param response 要保存的响应
     */
    public void complete(String principal, String key, StoredResponse response) {
        sql(completeSql)
                .param("principal", principal)
                .param("key", key)
                .param("response_status", response.status())
                .param("response_body", response.body())
                .update();
    }

    /**
     * 释放处理中的键，用于首次请求执行失败、没有可保存的结果时，让调用方可以用同一个键重试。
     *
     * @param principal 调用方身份
     * @param key 幂等键
     */
    public void release(String principal, String key) {
        sql(releaseSql).param("principal", principal).param("key", key).update();
    }

    /**
     * 幂等键的一条记录。
     *
     * @param requestHash 首次请求的指纹
     * @param completed 是否已保存结果
     * @param responseStatus 首次请求的 HTTP 状态码；未完成时为空
     * @param responseBody 首次请求的响应体；可为空
     */
    public record Entry(String requestHash, boolean completed, Integer responseStatus, String responseBody) {}
}
