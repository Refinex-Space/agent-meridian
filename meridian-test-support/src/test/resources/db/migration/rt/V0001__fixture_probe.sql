-- 夹具自测用的迁移：验证 TestDatabase.migrate 按约定从 db/migration/<schema> 加载脚本。
CREATE TABLE fixture_probes (
    id   text PRIMARY KEY,
    body text NOT NULL
);
COMMENT ON TABLE fixture_probes IS '测试夹具自测表';
