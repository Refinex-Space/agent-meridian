-- 连通性检查的最近一次结果（T19b，ADR-0053）：供应商账号的连通性测试与 MCP 服务的探测，控制台据此显示连接状态。

ALTER TABLE provider_accounts ADD COLUMN last_check jsonb;
ALTER TABLE mcp_servers ADD COLUMN last_check jsonb;

COMMENT ON COLUMN provider_accounts.last_check IS '最近一次连通性测试的结果 {status, http_status, latency_ms, message, checked_at}；从未测试过时为空';
COMMENT ON COLUMN mcp_servers.last_check IS '最近一次探测（登记、修改、重新探测）的结果，结构同 provider_accounts.last_check；T19b 之前没有';
