package cn.refinex.meridian.persistence.tenant;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.refinex.meridian.common.context.PlatformContext;
import cn.refinex.meridian.common.context.PlatformContexts;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

/** 不连数据库：作用域校验在执行 SQL 之前完成。 */
class TenantScopedRepositoryTest {

    private static final DataSource UNUSED = new SimpleDriverDataSource();
    private static final PlatformContext PROJECT_CONTEXT =
            PlatformContext.builder().tenantId("ten_A").projectId("prj_A").build();
    private static final PlatformContext TENANT_CONTEXT =
            PlatformContext.builder().tenantId("ten_A").build();

    static class Probe extends TenantScopedRepository {
        Probe(TenantScope scope) {
            super(UNUSED, scope);
        }

        ScopedStatement statement(String sql) {
            return sql(sql);
        }
    }

    @Test
    void sqlWithoutTenantPlaceholderIsRejected() {
        Probe probe = new Probe(TenantScope.TENANT);

        PlatformContexts.run(
                TENANT_CONTEXT,
                () -> assertThatThrownBy(() -> probe.statement("SELECT * FROM rt.t WHERE id = :id"))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining(":tenant_id"));
    }

    @Test
    void projectScopeAlsoRequiresProjectPlaceholder() {
        Probe probe = new Probe(TenantScope.PROJECT);

        PlatformContexts.run(
                PROJECT_CONTEXT,
                () -> assertThatThrownBy(() -> probe.statement("SELECT * FROM rt.t WHERE tenant_id = :tenant_id"))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining(":project_id"));
    }

    @Test
    void castsAndLongerNamesDoNotCountAsPlaceholders() {
        Probe probe = new Probe(TenantScope.TENANT);

        PlatformContexts.run(TENANT_CONTEXT, () -> {
            assertThatThrownBy(() -> probe.statement("SELECT x::tenant_id FROM rt.t"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> probe.statement("SELECT * FROM rt.t WHERE tenant_id = :tenant_id_other"))
                    .isInstanceOf(IllegalArgumentException.class);
        });
    }

    @Test
    void missingContextIsRejected() {
        Probe probe = new Probe(TenantScope.TENANT);

        assertThatThrownBy(() -> probe.statement("SELECT * FROM rt.t WHERE tenant_id = :tenant_id"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void projectScopeRejectsContextWithoutProject() {
        Probe probe = new Probe(TenantScope.PROJECT);

        PlatformContexts.run(
                TENANT_CONTEXT,
                () -> assertThatThrownBy(() -> probe.statement(
                                "SELECT * FROM rt.t WHERE tenant_id = :tenant_id AND project_id = :project_id"))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("projectId"));
    }

    @Test
    void scopeParametersCannotBeOverridden() {
        Probe probe = new Probe(TenantScope.PROJECT);
        String sql = "SELECT * FROM rt.t WHERE tenant_id = :tenant_id AND project_id = :project_id";

        PlatformContexts.run(PROJECT_CONTEXT, () -> {
            assertThatThrownBy(() -> probe.statement(sql).param("tenant_id", "ten_B"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> probe.statement(sql).param("project_id", "prj_B"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatCode(() -> probe.statement(sql).param("id", "x")).doesNotThrowAnyException();
        });
    }
}
