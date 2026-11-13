package cn.refinex.meridian.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.runtime.support.DbAssertions;
import cn.refinex.meridian.runtime.support.RuntimeTestDatabase;
import cn.refinex.meridian.testsupport.MeridianContainers;
import cn.refinex.meridian.testsupport.TestDatabase;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.Container.ExecResult;

/**
 * T05-A01：{@code rt} 迁移在空库上执行成功，表、索引、分区、注释、外键与权限符合架构设计「数据架构」。
 *
 * <p>每次测试运行使用新的 PostgreSQL 容器，迁移在空的 {@code rt} schema 上执行。测试结束前用 psql 输出核心表的
 * {@code \d+}，作为验收的摘要证据（见 failsafe 报告的输出文件）。
 */
class RuntimeMigrationIT {

    private static final Logger log = LoggerFactory.getLogger(RuntimeMigrationIT.class);

    /** 架构设计「核心表」runtime 部分的全部表，加 outbox 与幂等键（各服务的第一个迁移复制自 meridian-persistence）。 */
    private static final List<String> CORE_TABLES = List.of(
            "sessions",
            "threads",
            "turns",
            "turn_inputs",
            "tenant_concurrency",
            "session_events",
            "inbox_messages",
            "agent_states",
            "tool_invocations",
            "pending_actions",
            "workspace_entries",
            "outbox",
            "idempotency_keys");

    /** 不带 project_id 的表：架构设计注明的租户并发计数，以及 persistence 提供的两张通用表。 */
    private static final List<String> WITHOUT_PROJECT = List.of("tenant_concurrency", "outbox", "idempotency_keys");

    @BeforeAll
    static void migrate() {
        RuntimeTestDatabase.dataSource();
    }

    @Test
    void migrationAppliedSuccessfully() {
        MigrationInfo[] applied = Flyway.configure()
                .dataSource(TestDatabase.migratorDataSource())
                .locations("classpath:db/migration/rt")
                .schemas("rt")
                .defaultSchema("rt")
                .createSchemas(false)
                .load()
                .info()
                .applied();

        assertThat(applied).isNotEmpty();
        assertThat(applied).allSatisfy(info -> assertThat(info.getState()).isEqualTo(MigrationState.SUCCESS));
        assertThat(applied[0].getVersion().getVersion()).isEqualTo("0001");
    }

    @Test
    void allCoreTablesExist() {
        List<String> tables = DbAssertions.list(
                "SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
                        + "WHERE n.nspname = 'rt' AND c.relkind IN ('r', 'p') AND NOT c.relispartition",
                String.class);

        assertThat(tables).containsAll(CORE_TABLES);
    }

    @Test
    void architectureKeyColumnsExist() {
        assertColumns(
                "sessions",
                "id",
                "agent_version_id",
                "environment_id",
                "environment_revision",
                "end_user_id",
                "interaction",
                "status",
                "last_seq",
                "sandbox_state",
                "sandbox_pod_ip",
                "sandbox_last_used_at",
                "purge_after");
        assertColumns("threads", "id", "session_id", "parent_thread_id", "parent_tool_use_id", "agent_name", "status");
        assertColumns(
                "turns",
                "id",
                "thread_id",
                "tenant_id",
                "kind",
                "status",
                "priority",
                "not_before",
                "lease_owner",
                "lease_epoch",
                "lease_expires_at",
                "claim_count",
                "recovery_attempt",
                "interrupt_requested_at",
                "stop_reason");
        assertColumns("turn_inputs", "turn_id", "payload");
        assertColumns("tenant_concurrency", "tenant_id", "running", "max_running");
        assertColumns(
                "session_events", "session_id", "seq", "id", "thread_id", "turn_id", "type", "data", "created_at");
        assertColumns(
                "inbox_messages",
                "id",
                "session_id",
                "thread_id",
                "event_id",
                "delivery",
                "status",
                "consumed_turn_id",
                "consumed_epoch");
        assertColumns(
                "agent_states",
                "user_id",
                "thread_id",
                "state_key",
                "state",
                "version",
                "content_epoch",
                "tenant_id",
                "project_id");
        assertColumns(
                "tool_invocations",
                "id",
                "turn_id",
                "thread_id",
                "tool_use_id",
                "tool_name",
                "side_effect",
                "idempotency_key",
                "status",
                "input",
                "result_ref");
        assertColumns(
                "pending_actions",
                "id",
                "turn_id",
                "thread_id",
                "type",
                "payload",
                "status",
                "decided_by",
                "remember_scope",
                "expires_at");
        assertColumns("workspace_entries", "namespace", "key", "version", "size", "inline_content", "object_key");
        assertColumns("outbox", "id", "topic", "payload", "created_at", "published_at");

        assertThat(columnType("agent_states", "state")).isEqualTo("jsonb");
        assertThat(columnType("session_events", "data")).isEqualTo("jsonb");
        assertThat(columnType("workspace_entries", "namespace")).isEqualTo("text[]");
    }

    @Test
    void businessTablesCarryTenantAndProject() {
        for (String table : CORE_TABLES) {
            List<String> columns = columns(table);
            if (!table.equals("outbox")) {
                assertThat(columns).as(table).contains("tenant_id");
            }
            if (!WITHOUT_PROJECT.contains(table)) {
                assertThat(columns).as(table).contains("project_id");
            }
        }
    }

    @Test
    void sessionEventsIsHashPartitionedIntoSixteenWithSessionSeqPrimaryKey() {
        assertThat(DbAssertions.list(
                        "SELECT pt.partstrat::text FROM pg_partitioned_table pt JOIN pg_class c ON c.oid = pt.partrelid "
                                + "JOIN pg_namespace n ON n.oid = c.relnamespace "
                                + "WHERE n.nspname = 'rt' AND c.relname = 'session_events'",
                        String.class))
                .containsExactly("h");
        assertThat(DbAssertions.list("SELECT pg_get_partkeydef('rt.session_events'::regclass)", String.class))
                .containsExactly("HASH (session_id)");
        List<String> bounds = DbAssertions.list(
                "SELECT pg_get_expr(c.relpartbound, c.oid) FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid "
                        + "WHERE i.inhparent = 'rt.session_events'::regclass ORDER BY c.relname",
                String.class);
        assertThat(bounds).hasSize(16);
        for (int remainder = 0; remainder < 16; remainder++) {
            assertThat(bounds.get(remainder)).isEqualTo("FOR VALUES WITH (modulus 16, remainder " + remainder + ")");
        }
        assertThat(primaryKey("session_events")).isEqualTo("PRIMARY KEY (session_id, seq)");
    }

    @Test
    void turnsHasPartialUniqueIndexOnActiveThreadAndClaimIndexes() {
        assertThat(indexDefinition("turns_thread_active_uidx"))
                .isEqualTo("CREATE UNIQUE INDEX turns_thread_active_uidx ON rt.turns USING btree (thread_id) "
                        + "WHERE (status = ANY (ARRAY['queued'::text, 'running'::text, 'awaiting_action'::text]))");
        assertThat(indexDefinition("turns_queued_idx"))
                .isEqualTo("CREATE INDEX turns_queued_idx ON rt.turns USING btree (priority, created_at) "
                        + "WHERE (status = 'queued'::text)");
        assertThat(indexDefinition("turns_lease_expiry_idx"))
                .isEqualTo("CREATE INDEX turns_lease_expiry_idx ON rt.turns USING btree (lease_expires_at) "
                        + "WHERE (status = 'running'::text)");
        assertThat(primaryKey("agent_states")).isEqualTo("PRIMARY KEY (user_id, thread_id, state_key)");
        assertThat(primaryKey("tenant_concurrency")).isEqualTo("PRIMARY KEY (tenant_id)");
    }

    @Test
    void foreignKeysStayWithinRuntimeSchema() {
        List<String> foreignKeys = DbAssertions.list(
                "SELECT conrelid::regclass::text || ' -> ' || confrelid::regclass::text FROM pg_constraint "
                        + "WHERE contype = 'f' AND connamespace = 'rt'::regnamespace "
                        + "AND conrelid::regclass::text NOT LIKE 'rt.session_events_p%'",
                String.class);

        assertThat(foreignKeys)
                .contains(
                        "rt.threads -> rt.sessions",
                        "rt.turns -> rt.threads",
                        "rt.turns -> rt.sessions",
                        "rt.turn_inputs -> rt.turns",
                        "rt.session_events -> rt.sessions",
                        "rt.agent_states -> rt.threads",
                        "rt.tool_invocations -> rt.turns",
                        "rt.pending_actions -> rt.turns");
        assertThat(foreignKeys).allSatisfy(fk -> assertThat(fk).matches("rt\\.\\S+ -> rt\\.\\S+"));
    }

    @Test
    void everyTablePartitionAndColumnHasAChineseComment() {
        List<String> tablesWithoutComment = DbAssertions.list(
                "SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
                        + "WHERE n.nspname = 'rt' AND c.relkind IN ('r', 'p') AND c.relname <> 'flyway_schema_history' "
                        + "AND obj_description(c.oid, 'pg_class') IS NULL",
                String.class);
        List<String> columnsWithoutComment = DbAssertions.list(
                "SELECT c.relname || '.' || a.attname FROM pg_attribute a JOIN pg_class c ON c.oid = a.attrelid "
                        + "JOIN pg_namespace n ON n.oid = c.relnamespace "
                        + "WHERE n.nspname = 'rt' AND c.relkind IN ('r', 'p') AND NOT c.relispartition "
                        + "AND c.relname <> 'flyway_schema_history' AND a.attnum > 0 AND NOT a.attisdropped "
                        + "AND col_description(c.oid, a.attnum) IS NULL",
                String.class);

        assertThat(tablesWithoutComment).isEmpty();
        assertThat(columnsWithoutComment).isEmpty();
        assertThat(DbAssertions.list(
                        "SELECT obj_description('rt.session_events_p07'::regclass, 'pg_class')", String.class))
                .containsExactly("session_events 的哈希分区，余数 7（共 16 个）");
    }

    @Test
    void applicationRoleHasOnlyDml() {
        for (String table : CORE_TABLES) {
            String qualified = "rt." + table;
            assertThat(privilege(qualified, "SELECT")).as(qualified).isTrue();
            assertThat(privilege(qualified, "INSERT")).as(qualified).isTrue();
            assertThat(privilege(qualified, "UPDATE")).as(qualified).isTrue();
            assertThat(privilege(qualified, "DELETE")).as(qualified).isTrue();
            assertThat(privilege(qualified, "TRUNCATE")).as(qualified).isFalse();
        }
        assertThat(privilege("rt.session_events_p00", "INSERT")).isTrue();
        assertThat(DbAssertions.list(
                        "SELECT has_schema_privilege(?, 'rt', 'CREATE')", Boolean.class, MeridianContainers.APP_USER))
                .containsExactly(false);
        assertThat(DbAssertions.list(
                        "SELECT tableowner FROM pg_tables WHERE schemaname = 'rt' AND tablename = 'turns'",
                        String.class))
                .containsExactly(MeridianContainers.MIGRATOR_USER);
    }

    /** 输出核心表的 {@code \d+}，作为 T05-A01 的摘要证据。 */
    @Test
    void describeCoreTablesForEvidence() throws Exception {
        for (String table : List.of("turns", "session_events", "agent_states", "tenant_concurrency")) {
            ExecResult result = MeridianContainers.postgres()
                    .execInContainer(
                            "psql",
                            "-U",
                            MeridianContainers.postgres().getUsername(),
                            "-d",
                            MeridianContainers.DATABASE,
                            "-c",
                            "\\d+ rt." + table);
            assertThat(result.getExitCode()).isZero();
            assertThat(result.getStdout()).contains("\"rt." + table + "\"");
            log.info("\\d+ rt.{}\n{}", table, result.getStdout());
        }
    }

    private static void assertColumns(String table, String... expected) {
        assertThat(columns(table)).as(table).contains(expected);
    }

    private static List<String> columns(String table) {
        return DbAssertions.list(
                "SELECT column_name FROM information_schema.columns WHERE table_schema = 'rt' AND table_name = ?",
                String.class,
                table);
    }

    private static String columnType(String table, String column) {
        return DbAssertions.list(
                        "SELECT format_type(a.atttypid, a.atttypmod) FROM pg_attribute a "
                                + "WHERE a.attrelid = ('rt.' || ?)::regclass AND a.attname = ?",
                        String.class,
                        table,
                        column)
                .getFirst();
    }

    private static String primaryKey(String table) {
        return DbAssertions.list(
                        "SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                                + "WHERE conrelid = ('rt.' || ?)::regclass AND contype = 'p'",
                        String.class,
                        table)
                .getFirst();
    }

    private static String indexDefinition(String index) {
        return DbAssertions.list(
                        "SELECT indexdef FROM pg_indexes WHERE schemaname = 'rt' AND indexname = ?",
                        String.class,
                        index)
                .getFirst();
    }

    private static boolean privilege(String table, String privilege) {
        return DbAssertions.list(
                        "SELECT has_table_privilege(?, ?, ?)",
                        Boolean.class,
                        MeridianContainers.APP_USER,
                        table,
                        privilege)
                .getFirst();
    }
}
