#!/usr/bin/env bash
# 首次初始化数据库：两个角色、三个 schema、扩展与中文全文检索配置。
#
# 角色（编码规范「数据访问」）：
#   meridian_migrator  拥有 cp、rt、kb 三个 schema，只由 Flyway 使用，执行 DDL。
#   meridian_app       应用运行时使用，只有 DML；不能建表、改表、删表，也不能 TRUNCATE。
# 口令来自环境变量 MERIDIAN_MIGRATOR_PASSWORD、MERIDIAN_APP_PASSWORD，本地值见 deploy/compose/.env.example。
set -euo pipefail

: "${MERIDIAN_MIGRATOR_PASSWORD:?需要设置 MERIDIAN_MIGRATOR_PASSWORD}"
: "${MERIDIAN_APP_PASSWORD:?需要设置 MERIDIAN_APP_PASSWORD}"

psql -v ON_ERROR_STOP=1 \
    --username "${POSTGRES_USER}" \
    --dbname "${POSTGRES_DB}" \
    --set migrator_password="${MERIDIAN_MIGRATOR_PASSWORD}" \
    --set app_password="${MERIDIAN_APP_PASSWORD}" \
    --set db_name="${POSTGRES_DB}" <<'SQL'
CREATE ROLE meridian_migrator LOGIN PASSWORD :'migrator_password';
CREATE ROLE meridian_app LOGIN PASSWORD :'app_password';

GRANT CONNECT ON DATABASE :"db_name" TO meridian_migrator, meridian_app;

-- 三个服务各自的 schema（架构设计「数据架构」），由迁移角色拥有
CREATE SCHEMA cp AUTHORIZATION meridian_migrator;
CREATE SCHEMA rt AUTHORIZATION meridian_migrator;
CREATE SCHEMA kb AUTHORIZATION meridian_migrator;

-- 应用角色：能访问 schema 中的对象，但不能在其中创建对象
GRANT USAGE ON SCHEMA cp, rt, kb TO meridian_app;

-- 迁移角色以后创建的表、序列自动授予应用角色 DML 权限
ALTER DEFAULT PRIVILEGES FOR ROLE meridian_migrator IN SCHEMA cp, rt, kb
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO meridian_app;
ALTER DEFAULT PRIVILEGES FOR ROLE meridian_migrator IN SCHEMA cp, rt, kb
    GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO meridian_app;

-- 扩展装在 public，所有角色可用；迁移与应用的 search_path 都包含 public
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS zhparser;

-- 中文全文检索配置：名词、动词、形容词、成语、叹词、习用语映射到 simple 词典
CREATE TEXT SEARCH CONFIGURATION public.chinese (PARSER = zhparser);
ALTER TEXT SEARCH CONFIGURATION public.chinese ADD MAPPING FOR n, v, a, i, e, l WITH simple;
SQL
