package cn.refinex.meridian.controlplane.member;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.api.admin.model.PermissionName;
import cn.refinex.meridian.security.rbac.Permission;
import java.util.Arrays;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/** 管理 API 中的操作类别取值（{@code PermissionName}）与 {@code security.rbac.Permission} 一一对应（ADR-0053）。 */
class PermissionNamesTest {

    @Test
    void theSpecListsEveryPermission() {
        assertThat(Arrays.stream(PermissionName.values()).map(PermissionName::getValue))
                .containsExactlyInAnyOrderElementsOf(Arrays.stream(Permission.values())
                        .map(p -> p.name().toLowerCase(Locale.ROOT))
                        .toList());
    }
}
