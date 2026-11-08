-- 会话沙箱的生命周期（T12a，架构设计「会话沙箱」）：空闲回收后留下的快照、vCPU 秒的计量起点、产物目录 outputs/ 同步到对象存储的清单。
-- sessions.sandbox_state 改存 JSON 对象 {"sandbox_id", "size", "state"}，state 为驱动序列化的沙箱状态（ADR-0037）。

ALTER TABLE sessions ADD COLUMN sandbox_snapshot_key text;
ALTER TABLE sessions ADD COLUMN sandbox_metered_at timestamptz;

COMMENT ON COLUMN sessions.sandbox_state IS
    '会话沙箱：{"sandbox_id": 沙箱资源名, "size": 规格, "state": 驱动序列化的沙箱状态}；为空表示没有沙箱或已回收';
COMMENT ON COLUMN sessions.sandbox_snapshot_key IS
    '最近一次空闲回收留下的工作区快照（rt/snapshots/…）；下一轮从它恢复，新的快照写入后旧的删除。为空表示没有快照';
COMMENT ON COLUMN sessions.sandbox_metered_at IS
    '沙箱 vCPU 秒已计量到的时间：认领或恢复时设为当时，每轮结束与回收时计量到当时；没有沙箱时为空';

-- ---------------------------------------------------------------------------------------------
-- 产物（sandbox 档 outputs/ 在对象存储中的副本）
-- ---------------------------------------------------------------------------------------------
CREATE TABLE sandbox_outputs (
    session_id text        NOT NULL REFERENCES sessions (id) ON DELETE CASCADE,
    path       text        NOT NULL,
    tenant_id  text        NOT NULL,
    project_id text        NOT NULL,
    size       bigint      NOT NULL CHECK (size >= 0),
    sha256     text        NOT NULL,
    object_key text        NOT NULL,
    synced_at  timestamptz NOT NULL,
    PRIMARY KEY (session_id, path)
);

COMMENT ON TABLE sandbox_outputs IS 'sandbox 档产物目录 outputs/ 同步到对象存储的清单：每轮结束时按 SHA-256 比对，增删改同步';
COMMENT ON COLUMN sandbox_outputs.session_id IS '会话 ID';
COMMENT ON COLUMN sandbox_outputs.path IS '相对 outputs/ 的路径，分隔符为 /';
COMMENT ON COLUMN sandbox_outputs.tenant_id IS '租户 ID';
COMMENT ON COLUMN sandbox_outputs.project_id IS '项目 ID';
COMMENT ON COLUMN sandbox_outputs.size IS '文件大小（字节）';
COMMENT ON COLUMN sandbox_outputs.sha256 IS '文件内容的 SHA-256（小写十六进制）';
COMMENT ON COLUMN sandbox_outputs.object_key IS '对象存储中的键：rt/outputs/{tenant}/{project}/{session}/{path}';
COMMENT ON COLUMN sandbox_outputs.synced_at IS '最近一次同步的时间';
