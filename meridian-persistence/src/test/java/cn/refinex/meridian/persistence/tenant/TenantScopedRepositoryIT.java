package cn.refinex.meridian.persistence.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.refinex.meridian.common.context.PlatformContext;
import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.common.id.IdPrefix;
import cn.refinex.meridian.common.id.Ids;
import cn.refinex.meridian.persistence.PersistenceFixtures;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** T02-A04：两种作用域下的租户与项目隔离。 */
class TenantScopedRepositoryIT {

    private static final Instant CREATED_AT = Instant.parse("2026-11-07T05:49:49.123456Z");

    private NoteRepository notes;
    private TenantSettingRepository settings;
    private PlatformContext tenantAProject1;
    private PlatformContext tenantAProject2;
    private PlatformContext tenantBProject1;

    @BeforeEach
    void setUp() {
        DataSource dataSource = PersistenceFixtures.dataSource();
        notes = new NoteRepository(dataSource);
        settings = new TenantSettingRepository(dataSource);
        String tenantA = Ids.newId(IdPrefix.TENANT);
        String tenantB = Ids.newId(IdPrefix.TENANT);
        String project1 = Ids.newId(IdPrefix.PROJECT);
        String project2 = Ids.newId(IdPrefix.PROJECT);
        tenantAProject1 = context(tenantA, project1);
        tenantAProject2 = context(tenantA, project2);
        // 租户 B 使用与租户 A 相同的项目 ID，验证隔离不只靠项目条件
        tenantBProject1 = context(tenantB, project1);
    }

    @Test
    void projectScopeIsolatesTenantsAndProjects() {
        PlatformContexts.run(tenantAProject1, () -> notes.insert("a1", "租户 A 项目 1", List.of("x", "y"), CREATED_AT));
        PlatformContexts.run(tenantAProject2, () -> notes.insert("a2", "租户 A 项目 2", List.of(), CREATED_AT));
        PlatformContexts.run(tenantBProject1, () -> notes.insert("b1", "租户 B 项目 1", List.of(), CREATED_AT));

        PlatformContexts.run(tenantAProject1, () -> {
            assertThat(notes.listIds()).containsExactly("a1");
            NoteRepository.Note note = notes.find("a1").orElseThrow();
            assertThat(note.tags()).containsExactly("x", "y");
            assertThat(note.createdAt()).isEqualTo(CREATED_AT);
            assertThat(notes.attributeSource("a1")).isEqualTo("a1");
            // 其他租户的数据读不到、改不到、删不掉
            assertThat(notes.find("b1")).isEmpty();
            assertThat(notes.updateBody("b1", "篡改")).isZero();
            assertThat(notes.delete("b1")).isZero();
            // 同租户的其他项目也不可见
            assertThat(notes.find("a2")).isEmpty();
            assertThat(notes.updateBody("a2", "篡改")).isZero();
            assertThat(notes.delete("a2")).isZero();
        });

        PlatformContexts.run(tenantBProject1, () -> {
            assertThat(notes.listIds()).containsExactly("b1");
            assertThat(notes.find("b1").orElseThrow().body()).isEqualTo("租户 B 项目 1");
        });
        PlatformContexts.run(tenantAProject2, () -> {
            assertThat(notes.find("a2").orElseThrow().body()).isEqualTo("租户 A 项目 2");
            assertThat(notes.updateBody("a2", "已修改")).isEqualTo(1);
        });
    }

    @Test
    void tenantScopeIsolatesTenantsButSpansProjects() {
        PlatformContexts.run(tenantAProject1, () -> settings.put("model", "A 的设置"));
        PlatformContexts.run(tenantBProject1, () -> settings.put("model", "B 的设置"));

        PlatformContexts.run(
                tenantAProject2, () -> assertThat(settings.values()).containsExactly("A 的设置"));
        PlatformContexts.run(
                tenantBProject1, () -> assertThat(settings.values()).containsExactly("B 的设置"));
        PlatformContext tenantAOnly =
                PlatformContext.builder().tenantId(tenantAProject1.tenantId()).build();
        PlatformContexts.run(tenantAOnly, () -> {
            assertThat(settings.deleteAll()).isEqualTo(1);
            assertThat(settings.values()).isEmpty();
        });
        PlatformContexts.run(
                tenantBProject1, () -> assertThat(settings.values()).containsExactly("B 的设置"));
    }

    @Test
    void missingContextFailsInsteadOfReadingEverything() {
        assertThatThrownBy(() -> notes.listIds()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> settings.values()).isInstanceOf(IllegalStateException.class);
        PlatformContext withoutProject =
                PlatformContext.builder().tenantId(tenantAProject1.tenantId()).build();
        PlatformContexts.run(
                withoutProject,
                () -> assertThatThrownBy(() -> notes.listIds()).isInstanceOf(IllegalStateException.class));
    }

    private static PlatformContext context(String tenantId, String projectId) {
        return PlatformContext.builder().tenantId(tenantId).projectId(projectId).build();
    }
}
