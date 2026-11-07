package cn.refinex.meridian.engine.agentscope.testkit.assembly;

import cn.refinex.meridian.engine.agentscope.assembly.ModelFactory;
import cn.refinex.meridian.engine.spi.spec.ModelSpec;
import io.agentscope.core.model.Model;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 记录请求的模型工厂替身：按主模型 ID 返回登记的模型，没有登记时返回默认模型。
 *
 * <p>线程安全：可以被并发装配调用。
 *
 * <p>见架构设计「测试策略」「模型调用」。
 */
public final class RecordingModelFactory implements ModelFactory {

    private final Model defaultModel;
    private final Map<String, Model> byModelId = new ConcurrentHashMap<>();
    private final List<ModelSpec> requested = new CopyOnWriteArrayList<>();

    /**
     * 创建工厂。
     *
     * @param defaultModel 没有按模型 ID 登记时返回的模型
     */
    public RecordingModelFactory(Model defaultModel) {
        this.defaultModel = defaultModel;
    }

    /**
     * 为某个主模型 ID 登记模型，例如给子智能体单独的脚本。
     *
     * @param modelId 主模型 ID
     * @param model 模型
     * @return 本工厂
     */
    public RecordingModelFactory register(String modelId, Model model) {
        byModelId.put(modelId, model);
        return this;
    }

    @Override
    public Model create(ModelSpec model) {
        requested.add(model);
        return byModelId.getOrDefault(model.modelId(), defaultModel);
    }

    /**
     * 收到的全部模型引用，按调用顺序。
     *
     * @return 模型引用
     */
    public List<ModelSpec> requested() {
        return List.copyOf(requested);
    }
}
