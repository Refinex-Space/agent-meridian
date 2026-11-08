-- 出网授权按沙箱 Pod 的源 IP 找会话（T12b，架构设计「沙箱出网」）：代理的每个请求都会查询，按 IP 建部分索引。
-- 不建唯一约束：沙箱在平台之外被重建时，旧 IP 可能被另一个会话的新 Pod 复用，两行同时记录同一 IP 的窗口里授权一律拒绝，
-- 而唯一约束会让新会话的沙箱落库失败。
CREATE INDEX sessions_sandbox_pod_ip_idx ON sessions (sandbox_pod_ip) WHERE sandbox_pod_ip IS NOT NULL;
