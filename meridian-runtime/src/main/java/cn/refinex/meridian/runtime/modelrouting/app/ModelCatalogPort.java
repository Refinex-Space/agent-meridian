package cn.refinex.meridian.runtime.modelrouting.app;

import cn.refinex.meridian.runtime.modelrouting.domain.CatalogModel;
import cn.refinex.meridian.runtime.modelrouting.domain.ProviderAccount;
import java.util.Optional;

/**
 * 供应商账号与模型目录的内部端口（runtime 内部，不在 engine-spi）。T09a 的开发实现读 {@code dev-config/providers}
 * （{@code infra.FileModelCatalog}）；T15a 起改为 control-plane 下发的目录。
 *
 * <p>方法会阻塞，响应式调用方经 {@code Blocking} 调用。
 */
public interface ModelCatalogPort {

    /**
     * 按模型 ID 查找模型。
     *
     * @param modelId 模型 ID
     * @return 模型；不存在时为空
     */
    Optional<CatalogModel> model(String modelId);

    /**
     * 按 ID 查找供应商账号。
     *
     * @param providerAccountId 供应商账号 ID
     * @return 账号；不存在时为空
     */
    Optional<ProviderAccount> account(String providerAccountId);
}
