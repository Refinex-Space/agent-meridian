package cn.refinex.meridian.runtime.modelrouting.app;

import cn.refinex.meridian.runtime.modelrouting.domain.CatalogEntry;
import java.util.Optional;

/**
 * 供应商账号与模型目录的内部端口（runtime 内部，不在 engine-spi）。生产实现 {@code infra.ControlPlaneModelCatalog} 经 control-plane 的
 * {@code GET /internal/v1/models/{model_id}} 读取并缓存（ADR-0045）；开发与测试 profile 另有读 {@code dev-config/providers} 的
 * {@code infra.FileModelCatalog}。
 *
 * <p>方法会阻塞，响应式调用方经 {@code Blocking} 调用。不按租户过滤：调用方用 {@code CatalogModel.usableBy} 校验。
 */
public interface ModelCatalogPort {

    /**
     * 按模型 ID 查找模型与它的供应商账号。
     *
     * @param modelId 模型 ID
     * @return 目录项；不存在时为空
     */
    Optional<CatalogEntry> find(String modelId);
}
